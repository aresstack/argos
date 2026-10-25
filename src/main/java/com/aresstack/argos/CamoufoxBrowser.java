package com.aresstack.argos;

import com.aresstack.argos.page.DocumentType;
import com.aresstack.argos.page.NetworkActivity;
import com.aresstack.argos.page.PageSample;
import com.aresstack.argos.page.PageSnapshot;
import com.aresstack.argos.page.PageSource;
import com.aresstack.argos.page.ReadinessPolicy;
import com.aresstack.argos.page.ReadinessReport;
import com.aresstack.argos.page.ReadinessWaiter;
import com.aresstack.argos.page.SnapshotReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Owns one persistent Camoufox browser and serializes every Playwright API call
 * onto a single driver thread. Never attaches to an existing user's browser.
 */
public final class CamoufoxBrowser implements AutoCloseable {
    private static final int MAX_NETWORK_EVENTS = 200;
    /** Source documents above this size are not pulled through the protocol for a snapshot. */
    private static final long MAX_SOURCE_BYTES = 8_000_000;
    private static final int PROBE_TIMEOUT_MS = 2_000;
    private static final long SAVED_DOCUMENT_WAIT_NANOS = 5_000_000_000L;
    /** Requests whose transfer ended, from context-level protocol events (driver thread); by identity, weak. */
    private final java.util.Set<Request> finishedRequests =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final java.util.Set<Request> failedRequests =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final ArgosConfig config;
    private final ExecutorService driver = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().name("argos-camoufox-driver").unstarted(runnable));
    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<Map<String, Object>> network = new ArrayDeque<>();
    private final NetworkActivity activity = new NetworkActivity(System::currentTimeMillis);
    private final SnapshotReader snapshots = new SnapshotReader();
    private MainDocument mainDocument;
    /** Last top-level response the browser saved as a download; it never replaces the rendered page. */
    private MainDocument lastDownload;
    private Download download;
    /** A feed, XML, JSON or text document Firefox saved instead of rendering, read from the saved file. */
    private SavedDocument saved;
    private Playwright playwright;
    private BrowserContext context;
    private Page page;

    public CamoufoxBrowser(ArgosConfig config) {
        this.config = config;
    }

    public <T> T onBrowser(Callable<T> action) {
        try {
            Future<T> result = driver.submit(action);
            return result.get(90, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Browser task interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Camoufox operation failed: " + e.getMessage(), e);
        }
    }

    private Page activePage() throws Exception {
        if (page != null && !page.isClosed()) return page;
        if (context == null) {
            config.requireBrowserConfiguration();
            Files.createDirectories(config.profile());
            JsonNode launch = mapper.readTree(Files.readString(config.launchOptions()));
            JsonNode variables = launch.path("env");
            if (!variables.isObject() || !variables.has("CAMOU_CONFIG_1")) {
                throw new IllegalArgumentException("Official Camoufox fingerprint file requires env.CAMOU_CONFIG_1");
            }
            Map<String, String> environment = new HashMap<>(System.getenv());
            variables.fields().forEachRemaining(entry -> {
                if (!entry.getKey().startsWith("CAMOU_") && !entry.getKey().startsWith("FONTCONFIG")) {
                    throw new IllegalArgumentException("Unexpected fingerprint environment key: " + entry.getKey());
                }
                if (!entry.getValue().isTextual()) {
                    throw new IllegalArgumentException("Invalid fingerprint environment value: " + entry.getKey());
                }
                environment.put(entry.getKey(), entry.getValue().asText());
            });
            Map<String, Object> prefs = new LinkedHashMap<>();
            JsonNode preferences = launch.has("firefoxUserPrefs") ? launch.get("firefoxUserPrefs")
                    : launch.path("firefox_user_prefs");
            preferences.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isBoolean()) prefs.put(entry.getKey(), value.asBoolean());
                else if (value.isIntegralNumber()) prefs.put(entry.getKey(), value.asInt());
                else if (value.isTextual()) prefs.put(entry.getKey(), value.asText());
                else throw new IllegalArgumentException("Unsupported Firefox pref: " + entry.getKey());
            });
            List<String> args = new ArrayList<>();
            launch.path("args").forEach(value -> args.add(value.asText()));
            BrowserType.LaunchPersistentContextOptions options =
                    new BrowserType.LaunchPersistentContextOptions()
                            .setChannel("moz-firefox")
                            .setExecutablePath(config.executable())
                            .setHeadless(config.headless())
                            .setEnv(environment)
                            .setViewportSize(null)
                            .setColorScheme(null)
                            .setReducedMotion(null)
                            .setForcedColors(null)
                            .setContrast(null)
                            .setFirefoxUserPrefs(prefs);
            if (!args.isEmpty()) options.setArgs(args);
            playwright = Playwright.create();
            try {
                context = playwright.firefox().launchPersistentContext(config.profile(), options);
            } catch (RuntimeException error) {
                playwright.close();
                playwright = null;
                throw error;
            }
            // Protocol events: when a download's transfer ended (its request may not belong to the page any more).
            context.onRequestFinished(finishedRequests::add);
            context.onRequestFailed(failedRequests::add);
        }
        Page current = context.pages().stream().filter(p -> !p.isClosed()).findFirst()
                .orElseGet(context::newPage);
        page = current;
        mainDocument = null;
        lastDownload = null;
        download = null;
        saved = null;
        activity.reset();
        // Protocol events only: observing them is invisible to the website.
        current.onRequest(request -> {
            if (isMainDocument(current, request)) {
                activity.reset();
                saved = null; // the page loads a new document
            }
            activity.started();
            record("request", request.method(), request.url(), 0);
        });
        current.onDownload(started -> download = started);
        current.onFrameNavigated(frame -> {
            if (frame == current.mainFrame()) saved = null; // also same-document navigations
        });
        current.onResponse(response -> {
            if (isMainDocument(current, response.request())) {
                MainDocument document = new MainDocument(response);
                if (document.attachment()) lastDownload = document;
                else mainDocument = document;
            }
            record("response", response.request().method(), response.url(), response.status());
        });
        current.onRequestFinished(request -> {
            activity.ended();
            MainDocument main = mainDocument;
            if (main != null && main.isFor(request)) main.finished = true;
        });
        current.onRequestFailed(request -> {
            activity.ended();
            record("failed", request.method(), request.url(), 0);
        });
        return current;
    }

    private static boolean isMainDocument(Page owner, Request request) {
        try {
            return request.isNavigationRequest() && request.frame() == owner.mainFrame();
        } catch (RuntimeException workerOrDetachedFrame) {
            return false;
        }
    }

    private void record(String kind, String method, String originalUrl, int status) {
        String sanitized = originalUrl;
        try {
            java.net.URI uri = java.net.URI.create(originalUrl);
            sanitized = new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            // Unknown schemes may not have a host; keep only the scheme.
            int colon = originalUrl.indexOf(':');
            if (colon >= 0) sanitized = originalUrl.substring(0, colon + 1);
        }
        if (network.size() >= MAX_NETWORK_EVENTS) network.removeFirst();
        network.addLast(Map.of("at", Instant.now().toString(), "kind", kind,
                "method", method, "url", sanitized, "status", status));
    }

    public Map<String, Object> navigate(String target) {
        return onBrowser(() -> {
            java.net.URI destination = java.net.URI.create(target);
            if (destination.getScheme() == null
                    || !(destination.getScheme().equalsIgnoreCase("http")
                    || destination.getScheme().equalsIgnoreCase("https"))
                    || destination.getHost() == null) {
                throw new IllegalArgumentException("Navigation requires an absolute http(s) URL");
            }
            Page p = activePage();
            ReadinessPolicy policy = config.readiness();
            MainDocument previousDocument = mainDocument;
            MainDocument previousDownload = lastDownload;
            Response response;
            try {
                response = p.navigate(target, new Page.NavigateOptions()
                        .setWaitUntil(waitUntil(policy)).setTimeout(45_000));
            } catch (PlaywrightException error) {
                // Only a response of this navigation may turn the failure into a download.
                MainDocument download = lastDownload != previousDownload ? lastDownload
                        : mainDocument != previousDocument ? mainDocument : null;
                if (!isDownload(error, download)) throw error;
                mainDocument = previousDocument; // the previous page stays rendered
                Optional<SavedDocument> source = savedSourceDocument(p, download);
                if (source.isPresent()) {
                    saved = source.get();
                    return savedResult(saved);
                }
                return downloadResult(target, download);
            }
            // Keep the listener's instance: it may already carry the requestfinished flag.
            if (response != null && isMainDocument(p, response.request())
                    && (mainDocument == null || !mainDocument.isFor(response.request()))) {
                mainDocument = new MainDocument(response);
            }
            ReadinessReport readiness = settle(p, policy, null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", p.url());
            result.put("title", p.title());
            result.put("status", response == null ? 0 : response.status());
            result.put("documentType", documentType(p));
            result.put("contentType", contentType());
            result.put("readiness", readiness);
            return result;
        });
    }

    /**
     * Waits until the configured readiness conditions hold, optionally also for a
     * visible element ({@code selector}) and/or visible {@code text}.
     */
    public Map<String, Object> waitFor(String selector, String text, int timeoutMillis) {
        return onBrowser(() -> {
            Page p = activePage();
            if (saved != null) return savedWaitResult(saved, selector, text);
            ReadinessPolicy configured = config.readiness().settles() ? config.readiness() : ReadinessPolicy.balanced();
            ReadinessPolicy policy = configured.withTimeout(Math.max(500, Math.min(60_000, timeoutMillis)));
            Locator target = null;
            if (selector != null && !selector.isBlank()) {
                target = p.locator(selector);
                if (text != null && !text.isBlank()) target = target.filter(new Locator.FilterOptions().setHasText(text));
            } else if (text != null && !text.isBlank()) {
                target = p.getByText(text);
            }
            ReadinessReport readiness = target == null ? settle(p, policy, null)
                    : awaitConditions(p, policy, target);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", p.url());
            result.put("title", p.title());
            result.put("documentType", documentType(p));
            result.put("readiness", readiness);
            return result;
        });
    }

    public PageSnapshot snapshot(int maxCharacters) {
        return onBrowser(() -> {
            Page p = activePage();
            if (saved != null) return snapshots.read(new SavedPageSource(saved), maxCharacters);
            return snapshots.read(new PlaywrightPageSource(p, mainDocument), maxCharacters);
        });
    }

    private ReadinessReport settle(Page p, ReadinessPolicy policy, Locator target) throws Exception {
        if (!policy.settles()) return ReadinessReport.skipped();
        DocumentType type = documentType(p);
        if (type.isSourceDocument() || type == DocumentType.BINARY) return ReadinessReport.staticDocument();
        return awaitConditions(p, policy, target);
    }

    private ReadinessReport awaitConditions(Page p, ReadinessPolicy policy, Locator target) throws Exception {
        ReadinessWaiter waiter = new ReadinessWaiter(System::currentTimeMillis, p::waitForTimeout);
        return waiter.await(policy, target != null, () -> sample(p, policy, target));
    }

    /**
     * Observes the page through Playwright's isolated utility world (CSS locators,
     * text queries) and protocol events. Nothing runs in the page's own world.
     */
    private PageSample sample(Page p, ReadinessPolicy policy, Locator target) {
        Locator.FilterOptions visible = new Locator.FilterOptions().setVisible(true);
        boolean targetVisible = target == null || target.filter(visible).count() > 0;
        if (!policy.needsDomReads()) {
            // Each utility-world read grants user activation on BiDi (playwright4j#37): read nothing else.
            return new PageSample(0, 0, false, targetVisible, activity.inflight(), activity.quietMillis());
        }
        int elements = p.locator("*").count();
        int textLength = visibleText(p).length();
        boolean loading = !policy.loadingSelectors().isEmpty()
                && p.locator(String.join(", ", policy.loadingSelectors())).filter(visible).count() > 0;
        return new PageSample(elements, textLength, loading, targetVisible, activity.inflight(), activity.quietMillis());
    }

    private static String visibleText(Page p) {
        Locator body = p.locator("body");
        if (body.count() > 0) return body.first().innerText(new Locator.InnerTextOptions().setTimeout(PROBE_TIMEOUT_MS));
        Locator root = p.locator(":root");
        if (root.count() == 0) return "";
        String text = root.first().textContent(new Locator.TextContentOptions().setTimeout(PROBE_TIMEOUT_MS));
        return text == null ? "" : text;
    }

    private DocumentType documentType(Page p) {
        DocumentType type = saved != null ? DocumentType.classify(saved.contentType(), saved.url())
                : DocumentType.classify(contentType(), p.url());
        return type == DocumentType.UNKNOWN ? DocumentType.HTML : type;
    }

    private String contentType() {
        if (saved != null) return saved.contentType();
        return mainDocument == null ? null : mainDocument.contentType();
    }

    private static WaitUntilState waitUntil(ReadinessPolicy policy) {
        return switch (policy.waitUntil()) {
            case "commit" -> WaitUntilState.COMMIT;
            case "load" -> WaitUntilState.LOAD;
            default -> WaitUntilState.DOMCONTENTLOADED;
        };
    }

    private static boolean isDownload(PlaywrightException error, MainDocument download) {
        if (String.valueOf(error.getMessage()).contains("Download is starting")) return true;
        return download != null && (download.attachment()
                || DocumentType.classify(download.contentType(), download.url()) == DocumentType.BINARY);
    }

    /** A source document the browser downloaded; current until the page loads another document. */
    private record SavedDocument(String url, int status, String contentType, String text) { }

    /**
     * Firefox saves feeds ({@code application/rss+xml}, {@code application/atom+xml}) and some other source
     * documents as downloads instead of rendering them. Their text is read from the saved file: nothing of
     * this reaches the page. An attachment ({@code Content-Disposition}) stays a download.
     */
    private Optional<SavedDocument> savedSourceDocument(Page p, MainDocument response) {
        if (response == null || response.attachment()) return Optional.empty();
        DocumentType type = DocumentType.classify(response.contentType(), response.url());
        if (!type.isSourceDocument()) return Optional.empty();
        Long length = response.contentLength();
        if (length != null && length > MAX_SOURCE_BYTES) return Optional.empty();
        // Never wait for an unfinished download on the driver thread (Download.path() has no deadline): only a
        // download the browser reports finished within a few seconds is read, anything else stays a download.
        long deadline = System.nanoTime() + SAVED_DOCUMENT_WAIT_NANOS;
        String url = response.url();
        if (!pumpUntil(p, () -> download != null && url.equals(download.url()), deadline)) return Optional.empty();
        Download file = download;
        download = null;
        // This response's own request: an earlier transfer of the same URL may end in the meantime.
        Request request = response.request();
        if (!pumpUntil(p, () -> finishedRequests.contains(request) || failedRequests.contains(request), deadline)
                || failedRequests.contains(request)) {
            return Optional.empty(); // still loading or failed: the browser keeps it as a download
        }
        try {
            Path path = file.path(); // the transfer has ended, so this returns promptly
            try {
                if (path == null || Files.size(path) > MAX_SOURCE_BYTES) return Optional.empty();
                String text = decode(Files.readAllBytes(path), response.contentType(), type);
                return Optional.of(new SavedDocument(url, response.status(), response.contentType(), text));
            } finally {
                file.delete();
            }
        } catch (java.io.IOException | RuntimeException unavailable) {
            return Optional.empty(); // reported as a download, as before
        }
    }

    /** Waits by pumping Playwright events on this thread until {@code done} holds or {@code deadline} passed. */
    private static boolean pumpUntil(Page p, java.util.function.BooleanSupplier done, long deadline) {
        while (!done.getAsBoolean()) {
            if (System.nanoTime() >= deadline || p.isClosed()) return done.getAsBoolean();
            p.waitForTimeout(50);
        }
        return true;
    }

    /**
     * Text of a saved source document: the charset of the Content-Type header, else a byte order mark, else for
     * XML the encoding of the XML declaration, else UTF-8.
     */
    static String decode(byte[] bytes, String contentType, DocumentType type) {
        java.nio.charset.Charset declared = charset(contentType);
        if (declared != null) return new String(bytes, declared);
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return new String(bytes, 3, bytes.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, 2, bytes.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new String(bytes, 2, bytes.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        }
        if (type == DocumentType.XML || type == DocumentType.FEED) {
            String head = new String(bytes, 0, Math.min(bytes.length, 200), java.nio.charset.StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher xml = java.util.regex.Pattern.compile(
                    "^\\s*<\\?xml[^>]*\\bencoding\\s*=\\s*[\"']([\\w.:-]+)[\"']").matcher(head);
            if (xml.find()) {
                try {
                    return new String(bytes, java.nio.charset.Charset.forName(xml.group(1)));
                } catch (RuntimeException unknown) {
                    // fall through to UTF-8
                }
            }
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The charset parameter of a Content-Type header, or null when absent or unknown. */
    private static java.nio.charset.Charset charset(String contentType) {
        if (contentType != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("charset=\"?([\\w.:-]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contentType);
            if (m.find()) {
                try {
                    return java.nio.charset.Charset.forName(m.group(1));
                } catch (RuntimeException unknown) {
                    // fall through
                }
            }
        }
        return null;
    }

    /**
     * A saved feed is complete and has no page: nothing to wait for. Text is looked up in what the snapshot shows
     * of it (case and spacing ignored, like a text query on a page); an element selector cannot match it.
     */
    private Map<String, Object> savedWaitResult(SavedDocument document, String selector, String text) {
        Map<String, Object> result = savedResult(document);
        boolean bySelector = selector != null && !selector.isBlank();
        if (bySelector || (text != null && !text.isBlank())) {
            boolean found = !bySelector && looseText(snapshots.read(new SavedPageSource(document),
                    SnapshotReader.MAX_CHARACTERS).text()).contains(looseText(text));
            result.put("readiness", found
                    ? new ReadinessReport(ReadinessReport.READY, 0,
                            List.of("document-loaded", ReadinessWaiter.TARGET_VISIBLE), List.of())
                    : new ReadinessReport(ReadinessReport.TIMEOUT, 0, List.of("document-loaded"),
                            List.of(ReadinessWaiter.TARGET_VISIBLE)));
        }
        return result;
    }

    private static String looseText(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, Object> savedResult(SavedDocument saved) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", saved.url());
        result.put("title", "");
        result.put("status", saved.status());
        result.put("documentType", DocumentType.classify(saved.contentType(), saved.url()));
        if (saved.contentType() != null) result.put("contentType", saved.contentType());
        result.put("readiness", ReadinessReport.staticDocument());
        return result;
    }

    private static Map<String, Object> downloadResult(String target, MainDocument download) {
        String type = download == null ? null : download.contentType();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", download == null ? target : download.url());
        result.put("title", "");
        result.put("status", download == null ? 0 : download.status());
        result.put("documentType", DocumentType.BINARY);
        if (type != null) result.put("contentType", type);
        result.put("hint", "The browser treated this URL as a download, so no page was rendered. "
                + "Use document processing (download and extract) to read it.");
        return result;
    }

    /** The top-level document response; its body is fetched lazily and only once. */
    private static final class MainDocument {
        private final Response response;
        private final String url;
        private final int status;
        private final String contentType;
        private final boolean attachment;
        private final Long contentLength;
        /** Set from the protocol's requestfinished event (driver thread). */
        boolean finished;
        private Optional<String> body;

        MainDocument(Response response) {
            this.response = response;
            this.url = response.url();
            this.status = response.status();
            this.contentType = response.headerValue("content-type");
            String length = response.headerValue("content-length");
            this.contentLength = length != null && length.trim().matches("\\d{1,18}") ? Long.valueOf(length.trim()) : null;
            String disposition = response.headerValue("content-disposition");
            this.attachment = disposition != null && disposition.toLowerCase(java.util.Locale.ROOT).startsWith("attachment");
        }

        String url() { return url; }
        int status() { return status; }
        String contentType() { return contentType; }
        boolean attachment() { return attachment; }
        /** The declared Content-Length, or null. */
        Long contentLength() { return contentLength; }
        Request request() { return response.request(); }

        boolean isFor(Request request) {
            return response.request() == request;
        }

        /**
         * Response bodies travel over the protocol; the page cannot observe this read. Only a finished
         * response is read: asking for an unfinished (streaming or stalled) body would block the single
         * driver thread.
         */
        Optional<String> body() {
            if (body != null) return body;
            if (!finished) return Optional.empty(); // fall back to the DOM; a later read may get the body
            try {
                String length = response.headerValue("content-length");
                if (length != null && Long.parseLong(length.trim()) > MAX_SOURCE_BYTES) return body = Optional.empty();
                body = Optional.ofNullable(response.text());
            } catch (RuntimeException unavailable) {
                // Some protocol versions do not retain navigation bodies; fall back to the DOM.
                body = Optional.empty();
            }
            return body;
        }
    }

    /** A downloaded source document: everything comes from the saved file, nothing from the page. */
    private record SavedPageSource(SavedDocument saved) implements PageSource {
        @Override public String url() { return saved.url(); }
        @Override public String title() { return ""; }
        @Override public int status() { return saved.status(); }
        @Override public String contentType() { return saved.contentType(); }
        @Override public Optional<String> responseText() { return Optional.of(saved.text()); }
        @Override public boolean hasBody() { return false; }
        @Override public String bodyText() { return ""; }
        @Override public String rootText() { return saved.text(); }
        @Override public String documentSource() { return saved.text(); }
    }

    /** Reads the current document via protocol data and Playwright's utility world only. */
    private static final class PlaywrightPageSource implements PageSource {
        private final Page page;
        private final MainDocument document;
        private Boolean hasBody;

        PlaywrightPageSource(Page page, MainDocument document) {
            this.page = page;
            this.document = document;
        }

        @Override public String url() { return page.url(); }
        @Override public String title() { return page.title(); }
        @Override public int status() { return document == null ? 0 : document.status(); }
        @Override public String contentType() { return document == null ? null : document.contentType(); }

        @Override
        public Optional<String> responseText() {
            return document == null ? Optional.empty() : document.body();
        }

        @Override
        public boolean hasBody() {
            if (hasBody == null) hasBody = page.locator("body").count() > 0;
            return hasBody;
        }

        @Override
        public String bodyText() {
            return page.locator("body").first().innerText(new Locator.InnerTextOptions().setTimeout(PROBE_TIMEOUT_MS));
        }

        @Override
        public String rootText() {
            Locator root = page.locator(":root");
            if (root.count() == 0) return "";
            String text = root.first().textContent(new Locator.TextContentOptions().setTimeout(PROBE_TIMEOUT_MS));
            return text == null ? "" : text;
        }

        @Override
        public String documentSource() {
            return page.content();
        }
    }

    public Map<String, Object> inspect(String selector, int limit) {
        return onBrowser(() -> {
            Page p = activePage();
            Object value = p.evaluate("""
                    ({selector,limit}) => [...document.querySelectorAll(selector)]
                      .slice(0,limit).map(node => ({
                        tag:node.tagName.toLowerCase(),
                        text:(node.innerText||node.textContent||'').slice(0,600),
                        id:node.id||null,
                        classes:node.className && typeof node.className==='string'
                          ? node.className.slice(0,180) : null,
                        href:node.getAttribute('href'),
                        role:node.getAttribute('role'),
                        ariaLabel:node.getAttribute('aria-label')
                      }))
                    """, Map.of("selector", selector, "limit", Math.min(50, Math.max(1, limit))));
            return Map.of("url", p.url(), "elements", value);
        });
    }

    public Map<String, Object> click(String selector) {
        return onBrowser(() -> {
            Page p = activePage();
            p.locator(selector).first().click();
            saved = null; // the click acted on the rendered page, which is the current document again
            return Map.of("clicked", selector, "url", p.url());
        });
    }

    public Map<String, Object> fill(String selector, String text) {
        return onBrowser(() -> {
            Page p = activePage();
            p.locator(selector).first().fill(text);
            saved = null;
            return Map.of("filled", selector, "url", p.url());
        });
    }

    public Object evaluate(String expression) {
        return onBrowser(() -> activePage().evaluate(expression));
    }

    public byte[] screenshot() {
        return onBrowser(() -> activePage().screenshot(new Page.ScreenshotOptions()
                .setFullPage(false).setTimeout(15_000)));
    }

    public List<Map<String, Object>> networkEvents() {
        return onBrowser(() -> List.copyOf(network));
    }

    public Map<String, Object> status() {
        return onBrowser(() -> Map.of("started", context != null, "profile", config.profile().toString(),
                "url", saved != null ? saved.url() : page == null || page.isClosed() ? "" : page.url()));
    }

    @Override
    public void close() {
        onBrowser(() -> {
            if (context != null) context.close();
            if (playwright != null) playwright.close();
            context = null;
            playwright = null;
            return null;
        });
        driver.shutdown();
    }
}
