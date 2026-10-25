package com.aresstack.argos.fetch;

import com.aresstack.argos.web.UrlPolicy;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One extra tab of the existing browser session that loads a single address and is then closed, or handed back
 * by {@link #release()} so that a batch can load its next address there.
 *
 * <p>The content is loaded by the browser's own network stack (same cookies, logins, TLS fingerprint and
 * request headers as a user's tab). Nothing is injected into the page: no init scripts, no main-world
 * evaluation and no in-page {@code fetch()}. The bytes come either from the navigation response (WebDriver
 * BiDi data collector / CDP) or, when the server sends an attachment, from the browser's own download.
 * The tab the user or agent currently works with is not touched.
 *
 * <p>Redirect hops of the tab's own navigation and the final address are checked against the {@link UrlPolicy}.
 * A refused hop closes the tab and the result is {@code BLOCKED}. This only keeps refused content out of tool
 * results and explains the refusal; the browser has already sent that hop. Enforcement lives at the
 * connection boundary (analysis section 5.4).
 *
 * <p>Must only be used on the browser driver thread, like every other Playwright object.
 */
public final class BackgroundTab implements AutoCloseable {
    private static final int DOWNLOAD_GRACE_MILLIS = 3_000;
    private static final String DEADLINE_TICK = "argos-deadline-tick";

    private final Page tab;
    private final String requestedUrl;
    private final AtomicReference<Download> download = new AtomicReference<>();
    private final BrowserContext context;
    private final boolean downloadsCancellable;
    private final java.util.Set<Request> finished = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> finishedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<Request, String> failed = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.function.Consumer<Request> onFinished = request -> {
        finished.add(request);
        finishedUrls.add(request.url());
    };
    private final java.util.function.Consumer<Request> onFailed =
            request -> failed.put(request, String.valueOf(request.failure()));
    /** Main-frame navigation requests of this load (redirect hops, page-triggered navigations). */
    private final java.util.Set<String> navigated = new java.util.HashSet<>();
    /**
     * Only a download of this load's own navigations counts: a reused tab's previous page may have started one
     * just before it was unloaded, and Firefox announces a download only after the transfer began.
     */
    private final java.util.function.Consumer<Download> onDownload = started -> {
        if (navigated.contains(WebAddress.withoutFragment(started.url()))) download.set(started);
    };
    private java.util.function.Consumer<Request> onRequest;
    private Response response;
    private FetchFailure failure;
    private String refusedHop;
    private boolean downloadSettled;
    /** The navigation committed with a response, whatever its status. */
    private boolean committed;
    private Page ticker;

    private BackgroundTab(BrowserContext context, Page reused, String requestedUrl, boolean downloadsCancellable) {
        this.context = context;
        this.requestedUrl = requestedUrl;
        this.downloadsCancellable = downloadsCancellable;
        this.tab = reused != null && !reused.isClosed() ? reused : newTab(context);
        // Context level: a download's request may no longer be attributed to the tab.
        context.onRequestFinished(onFinished);
        context.onRequestFailed(onFailed);
    }

    /**
     * A new tab. Leaving a page later (when the tab is reused) must not stop at a {@code beforeunload} prompt,
     * which Playwright would dismiss and so cancel the navigation; the handler is not visible to the page.
     */
    private static Page newTab(BrowserContext context) {
        Page page = context.newPage();
        page.onDialog(dialog -> {
            if ("beforeunload".equals(dialog.type())) dialog.accept();
            else dialog.dismiss();
        });
        return page;
    }

    /**
     * Opens a new tab and returns as soon as the navigation committed (response headers arrived) or became a
     * download. The rest of the page keeps loading in the browser while the caller starts further tabs.
     */
    /**
     * @param downloadsCancellable whether {@link Download#cancel()} works in this browser (CDP: yes; WebDriver
     *                             BiDi in Playwright 1.62: no)
     */
    public static BackgroundTab open(BrowserContext context, String url, double commitTimeoutMillis,
                                     UrlPolicy policy, boolean downloadsCancellable) {
        return open(context, null, url, commitTimeoutMillis, policy, downloadsCancellable);
    }

    /**
     * Like {@link #open(BrowserContext, String, double, UrlPolicy, boolean)}, in {@code reused} (a tab handed back
     * by {@link #release()}) when it is given and still open. Opening a tab can cost seconds (Camoufox on Windows).
     */
    public static BackgroundTab open(BrowserContext context, Page reused, String url, double commitTimeoutMillis,
                                     UrlPolicy policy, boolean downloadsCancellable) {
        BackgroundTab opened = new BackgroundTab(context, reused, url, downloadsCancellable);
        try {
            opened.load(url, commitTimeoutMillis, policy);
        } catch (RuntimeException unexpected) {
            opened.close(); // the browser went away, say: never leave the tab or its listeners behind
            throw unexpected;
        }
        return opened;
    }

    private void load(String url, double commitTimeoutMillis, UrlPolicy policy) {
        navigated.add(WebAddress.withoutFragment(url));
        tab.onDownload(onDownload);
        onRequest = request -> {
            if (!request.isNavigationRequest() || request.frame() != tab.mainFrame()) return;
            navigated.add(WebAddress.withoutFragment(request.url()));
            if (refusedHop != null || request.url().equals(url) || policy.permits(request.url())) return;
            refusedHop = request.url();
            tab.close(); // stop loading; the pending navigation fails and is reported as BLOCKED
        };
        tab.onRequest(onRequest);
        try {
            response = tab.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.COMMIT).setTimeout(commitTimeoutMillis));
        } catch (TimeoutError timeout) {
            failure = new FetchFailure(FetchFailure.Kind.TIMEOUT,
                    "No response within " + Math.round(commitTimeoutMillis / 1000) + " s");
        } catch (PlaywrightException error) {
            // A navigation that turns into a download is aborted; the download event may arrive just after.
            if (!awaitDownloadEvent()) {
                failure = new FetchFailure(FetchFailure.Kind.FAILED, navigationError(error));
            }
        }
        // Only a loaded page or download has a final address; error pages (chrome-error:, about:neterror) do not.
        if (refusedHop == null && failure == null && !tab.isClosed()
                && !policy.permits(finalUrl())) {
            refusedHop = finalUrl();
        }
        if (refusedHop != null) {
            failure = new FetchFailure(FetchFailure.Kind.BLOCKED, "Redirected to an address the URL policy "
                    + "does not allow: " + WebAddress.redacted(refusedHop));
        } else if (failure == null && download.get() == null && response != null) {
            failure = FetchFailure.forHttpStatus(response.status());
            committed = true; // an error status is still a loaded page
        }
    }

    private boolean awaitDownloadEvent() {
        for (int waited = 0; download.get() == null && waited < DOWNLOAD_GRACE_MILLIS; waited += 100) {
            if (tab.isClosed()) break;
            tab.waitForTimeout(100); // pumps Playwright events on this thread
        }
        return download.get() != null;
    }

    /** Failure that already happened while opening, or {@code null}. */
    public FetchFailure failure() {
        return failure;
    }

    public boolean isDownload() {
        return download.get() != null;
    }

    /** Whether the server declared an HTML document that the browser renders as a page. */
    public boolean isHtmlPage() {
        String type = contentType();
        return response != null && !isDownload()
                && (type.isEmpty() || type.equals("text/html") || type.equals("application/xhtml+xml"));
    }

    /** Media type without parameters, lower case; empty when unknown. */
    public String contentType() {
        String declared = declaredContentType();
        int semicolon = declared.indexOf(';');
        return (semicolon < 0 ? declared : declared.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private String declaredContentType() {
        if (response == null) return "";
        String value = response.headers().get("content-type");
        return value == null ? "" : value;
    }

    public int status() {
        return response == null ? 0 : response.status();
    }

    public String finalUrl() {
        if (isDownload()) return download.get().url();
        return tab.isClosed() ? requestedUrl : tab.url();
    }

    /**
     * Waits until the browser fired {@code load} for this tab, at most {@code timeoutMillis}. A page that is
     * still loading after that is read as it is; returns {@code false} in that case.
     */
    public boolean awaitLoad(double timeoutMillis) {
        if (failure != null || !isHtmlPage()) return true;
        try {
            tab.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            return true;
        } catch (TimeoutError timeout) {
            return false;
        }
    }

    public String title() {
        try {
            return isHtmlPage() ? tab.title() : "";
        } catch (PlaywrightException error) {
            return "";
        }
    }

    /** Rendered text of an HTML page (Playwright's isolated utility world, not the page's own world). */
    public String renderedText() {
        if (tab.locator("body").count() > 0) return tab.locator("body").innerText();
        String text = tab.locator(":root").textContent();
        return text == null ? "" : text;
    }

    /**
     * The raw bytes of the loaded resource, refusing anything larger than {@code maxBytes}. Never blocks the
     * browser thread longer than {@code timeoutMillis}: it waits by pumping Playwright events in short slices
     * until the browser reports the request finished, and only then reads the body or the downloaded file.
     */
    public FetchedResource content(long maxBytes, double timeoutMillis) {
        if (failure != null) throw failure;
        long deadline = System.nanoTime() + (long) (timeoutMillis * 1_000_000);
        Download file = download.get();
        if (file != null) return downloaded(file, maxBytes, deadline, timeoutMillis);
        if (response == null) throw new FetchFailure(FetchFailure.Kind.FAILED, "The browser delivered no response");
        String length = response.headers().get("content-length");
        if (length != null && length.matches("\\d{1,18}") && Long.parseLong(length) > maxBytes) {
            throw tooLarge(Long.parseLong(length), maxBytes);
        }
        Request request = response.request();
        if (!pumpUntil(() -> finished.contains(request) || failed.containsKey(request), deadline)) {
            throw timedOut(timeoutMillis);
        }
        if (failed.containsKey(request)) {
            throw new FetchFailure(FetchFailure.Kind.FAILED, "Transfer failed: " + failed.get(request));
        }
        byte[] body;
        try {
            body = response.body();
        } catch (PlaywrightException error) {
            throw new FetchFailure(FetchFailure.Kind.FAILED,
                    "The browser did not keep the response body (larger than its collector limit?)", error);
        }
        if (body.length > maxBytes) throw tooLarge(body.length, maxBytes);
        if (contentType().equals("application/pdf") && !startsWithPdfSignature(body)) {
            // Chromium's built-in viewer can replace the body with its own wrapper page.
            throw new FetchFailure(FetchFailure.Kind.FAILED,
                    "The browser returned its PDF viewer instead of the file");
        }
        return new FetchedResource(requestedUrl, finalUrl(), status(), declaredContentType(),
                filenameFromDisposition(response.headers().get("content-disposition")), body);
    }

    /**
     * A browser download. Download.failure()/path() wait for completion without a timeout, so they are only
     * called once completion is known. Over CDP the download can be cancelled, which ends that wait; over
     * WebDriver BiDi Playwright 1.62 cannot cancel downloads ({@code cancelDownload} is a no-op), so Argos waits
     * for the browser to report the download request finished and otherwise gives up with TIMEOUT, leaving the
     * transfer to the browser (its temporary file is removed when the browser exits).
     */
    private FetchedResource downloaded(Download file, long maxBytes, long deadline, double timeoutMillis) {
        try {
            if (downloadsCancellable) {
                beforeDeadline(deadline, file::cancel);
            } else if (!pumpUntil(() -> finishedUrls.contains(file.url()), deadline)) {
                throw new FetchFailure(FetchFailure.Kind.TIMEOUT, "Download not complete within "
                        + Math.round(timeoutMillis / 1000) + " s; it cannot be cancelled over WebDriver BiDi");
            }
            String problem = file.failure();
            downloadSettled = true;
            if ("canceled".equals(problem)) throw timedOut(timeoutMillis);
            if (problem != null) throw new FetchFailure(FetchFailure.Kind.FAILED, "Download failed: " + problem);
            Path path = file.path();
            long size = Files.size(path);
            if (size > maxBytes) throw tooLarge(size, maxBytes);
            return new FetchedResource(requestedUrl, file.url(), 0, "", file.suggestedFilename(),
                    Files.readAllBytes(path));
        } catch (FetchFailure known) {
            throw known;
        } catch (Exception error) {
            throw new FetchFailure(FetchFailure.Kind.FAILED, "Download could not be read", error);
        }
    }

    /** Waits in short, event-pumping slices until {@code done} holds; {@code false} once the deadline passed. */
    private boolean pumpUntil(java.util.function.BooleanSupplier done, long deadline) {
        while (!done.getAsBoolean()) {
            if (System.nanoTime() >= deadline || tab.isClosed()) return done.getAsBoolean();
            tab.waitForTimeout(50);
        }
        return true;
    }

    /**
     * CDP only: arms a console tick in a blank helper tab (about:blank, never a website). Playwright Java
     * dispatches events while the thread waits in Download.failure(), so the handler can cancel the download
     * once the deadline passed, which ends that wait.
     */
    private void beforeDeadline(long deadline, Runnable onExpiry) {
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        ticker = tab.context().newPage();
        ticker.onConsoleMessage(message -> {
            if (DEADLINE_TICK.equals(message.text()) && System.nanoTime() >= deadline
                    && expired.compareAndSet(false, true)) {
                onExpiry.run();
            }
        });
        ticker.evaluate("tick => setInterval(() => console.debug(tick), 250)", DEADLINE_TICK);
    }

    private static FetchFailure timedOut(double timeoutMillis) {
        return new FetchFailure(FetchFailure.Kind.TIMEOUT,
                "Content not complete within " + Math.round(timeoutMillis / 1000) + " s; canceled");
    }

    private static boolean startsWithPdfSignature(byte[] body) {
        String head = new String(body, 0, Math.min(body.length, 1024), java.nio.charset.StandardCharsets.ISO_8859_1);
        return head.contains("%PDF-");
    }

    private static FetchFailure tooLarge(long size, long maxBytes) {
        return new FetchFailure(FetchFailure.Kind.TOO_LARGE,
                "Document has " + size + " bytes; the limit is " + maxBytes + " bytes");
    }

    static String filenameFromDisposition(String disposition) {
        if (disposition == null) return "";
        for (String part : disposition.split(";")) {
            String item = part.trim();
            String lower = item.toLowerCase(Locale.ROOT);
            if (lower.startsWith("filename*=")) {
                String value = item.substring(10).replace("\"", "");
                int quote = value.indexOf("''");
                value = quote >= 0 ? value.substring(quote + 2) : value;
                return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        for (String part : disposition.split(";")) {
            String item = part.trim();
            if (item.toLowerCase(Locale.ROOT).startsWith("filename=")) {
                return item.substring(9).replace("\"", "").trim();
            }
        }
        return "";
    }

    private static final java.util.regex.Pattern NETWORK_ERROR = java.util.regex.Pattern.compile("(NS_ERROR_[A-Z_]+|net::ERR_[A-Z_]+)");

    static String navigationError(PlaywrightException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        java.util.regex.Matcher network = NETWORK_ERROR.matcher(message);
        if (network.find()) return "Navigation failed: " + network.group(1);
        int start = message.indexOf("message='");
        if (start >= 0) message = message.substring(start + 9);
        int newline = message.indexOf('\n');
        message = newline < 0 ? message : message.substring(0, newline);
        // Playwright repeats the URL (possibly with query tokens) in its message; drop it.
        int at = message.indexOf(" at http");
        return "Navigation failed: " + (at < 0 ? message : message.substring(0, at)).trim();
    }

    /**
     * Ends this load and hands the tab back for the next address instead of closing it, when that is safe: the
     * tab is open, its navigation committed or became a finished download, and nothing was refused. The page of
     * this address is unloaded first ({@code about:blank}, no network request), so its scripts cannot start a
     * download or navigation that would be credited to the next address, and the next address is always a new
     * document (never a same-document fragment navigation). Otherwise the tab is closed and {@code null}
     * returned. The caller closes a returned tab when it has no further use.
     */
    public Page release() {
        boolean reusable = !tab.isClosed() && refusedHop == null
                && (failure == null || committed)
                && (download.get() == null || downloadSettled);
        if (!reusable) {
            close();
            return null;
        }
        detach();
        try {
            tab.offDownload(onDownload);
            if (onRequest != null) tab.offRequest(onRequest);
            tab.navigate("about:blank", new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.COMMIT).setTimeout(5_000));
            return tab;
        } catch (PlaywrightException notUnloaded) {
            close();
            return null;
        }
    }

    /** Context listeners, the helper tab and a settled download file; the tab itself stays. */
    private void detach() {
        context.offRequestFinished(onFinished);
        context.offRequestFailed(onFailed);
        Download file = download.get();
        try {
            // delete() waits for an unfinished download, so only a settled one is deleted here.
            if (file != null && downloadSettled) file.delete();
            if (ticker != null && !ticker.isClosed()) ticker.close();
        } catch (PlaywrightException ignored) {
            // The browser may already be gone.
        }
    }

    /** Closes the tab and discards the browser's temporary download file. Never fails. */
    @Override
    public void close() {
        detach();
        try {
            if (!tab.isClosed()) tab.close();
        } catch (PlaywrightException ignored) {
            // Closing a tab of a crashed browser is not an error of the fetch.
        }
    }
}
