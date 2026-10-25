package com.aresstack.argos.fetch;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One extra tab of the existing browser session that loads a single address and is closed again.
 *
 * <p>The content is loaded by the browser's own network stack (same cookies, logins, TLS fingerprint and
 * request headers as a user's tab). Nothing is injected into the page: no init scripts, no main-world
 * evaluation and no in-page {@code fetch()}. The bytes come either from the navigation response (WebDriver
 * BiDi data collector / CDP) or, when the server sends an attachment, from the browser's own download.
 * The tab the user or agent currently works with is not touched.
 *
 * <p>Must only be used on the browser driver thread, like every other Playwright object.
 */
public final class BackgroundTab implements AutoCloseable {
    private static final int DOWNLOAD_GRACE_MILLIS = 3_000;
    private static final String DEADLINE_TICK = "argos-deadline-tick";

    private final Page tab;
    private final String requestedUrl;
    private final AtomicReference<Download> download = new AtomicReference<>();
    private Response response;
    private FetchFailure failure;

    private BackgroundTab(Page tab, String requestedUrl) {
        this.tab = tab;
        this.requestedUrl = requestedUrl;
    }

    /**
     * Opens a new tab and returns as soon as the navigation committed (response headers arrived) or became a
     * download. The rest of the page keeps loading in the browser while the caller starts further tabs.
     */
    public static BackgroundTab open(BrowserContext context, String url, double commitTimeoutMillis) {
        BackgroundTab opened = new BackgroundTab(context.newPage(), url);
        opened.tab.onDownload(opened.download::set);
        try {
            opened.response = opened.tab.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.COMMIT).setTimeout(commitTimeoutMillis));
        } catch (TimeoutError timeout) {
            opened.failure = new FetchFailure(FetchFailure.Kind.TIMEOUT,
                    "No response within " + Math.round(commitTimeoutMillis / 1000) + " s");
        } catch (PlaywrightException error) {
            // A navigation that turns into a download is aborted; the download event may arrive just after.
            if (!opened.awaitDownloadEvent()) {
                opened.failure = new FetchFailure(FetchFailure.Kind.FAILED, navigationError(error));
            }
        }
        if (opened.failure == null && opened.download.get() == null && opened.response != null) {
            opened.failure = FetchFailure.forHttpStatus(opened.response.status());
        }
        return opened;
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
     * The raw bytes of the loaded resource, refusing anything larger than {@code maxBytes}. A body or download
     * that is not complete within {@code timeoutMillis} is cancelled, so a stalling server cannot hold the
     * single browser thread.
     */
    public FetchedResource content(long maxBytes, double timeoutMillis) {
        if (failure != null) throw failure;
        Download file = download.get();
        if (file != null) {
            return beforeDeadline(timeoutMillis, file::cancel, () -> downloaded(file, maxBytes));
        }
        if (response == null) throw new FetchFailure(FetchFailure.Kind.FAILED, "The browser delivered no response");
        String length = response.headers().get("content-length");
        if (length != null && length.matches("\\d{1,18}") && Long.parseLong(length) > maxBytes) {
            throw tooLarge(Long.parseLong(length), maxBytes);
        }
        byte[] body;
        try {
            body = beforeDeadline(timeoutMillis, tab::close, response::body);
        } catch (FetchFailure known) {
            throw known;
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

    private FetchedResource downloaded(Download file, long maxBytes) {
        try {
            String problem = file.failure();
            if ("canceled".equals(problem)) throw new FetchFailure(FetchFailure.Kind.TIMEOUT, "Download canceled");
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

    /**
     * Runs a Playwright call that has no timeout of its own (download completion, response body). Playwright
     * Java dispatches events while the driver thread waits, so a console tick from a separate, blank helper tab
     * (about:blank, never a website) lets a handler run {@code onExpiry} once the deadline has passed.
     */
    private <T> T beforeDeadline(double timeoutMillis, Runnable onExpiry, java.util.function.Supplier<T> call) {
        long deadline = System.nanoTime() + (long) (timeoutMillis * 1_000_000);
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        Page ticker = tab.context().newPage();
        try {
            ticker.onConsoleMessage(message -> {
                if (DEADLINE_TICK.equals(message.text()) && System.nanoTime() >= deadline
                        && expired.compareAndSet(false, true)) {
                    onExpiry.run();
                }
            });
            ticker.evaluate("tick => setInterval(() => console.debug(tick), 250)", DEADLINE_TICK);
            T result = call.get();
            if (expired.get()) throw timedOut(timeoutMillis);
            return result;
        } catch (PlaywrightException error) {
            if (expired.get()) throw timedOut(timeoutMillis);
            throw error;
        } catch (FetchFailure failed) {
            if (expired.get()) throw timedOut(timeoutMillis);
            throw failed;
        } finally {
            try {
                ticker.close();
            } catch (PlaywrightException ignored) {
                // Browser gone; nothing left to stop.
            }
        }
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

    /** Closes the tab and discards the browser's temporary download file. Never fails. */
    @Override
    public void close() {
        Download file = download.get();
        try {
            if (file != null) file.delete();
        } catch (PlaywrightException ignored) {
            // The browser may already be gone.
        }
        try {
            if (!tab.isClosed()) tab.close();
        } catch (PlaywrightException ignored) {
            // Closing a tab of a crashed browser is not an error of the fetch.
        }
    }
}
