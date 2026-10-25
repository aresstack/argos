package com.aresstack.argos;

import com.aresstack.argos.web.BrowserPagePort;
import com.aresstack.argos.web.PageCapture;
import com.aresstack.argos.web.PageText;
import com.aresstack.argos.page.DocumentText;
import com.aresstack.argos.page.DocumentType;
import com.aresstack.argos.page.NetworkActivity;
import com.aresstack.argos.page.PageSample;
import com.aresstack.argos.page.PageSnapshot;
import com.aresstack.argos.page.PageSource;
import com.aresstack.argos.page.ReadinessPolicy;
import com.aresstack.argos.page.ReadinessReport;
import com.aresstack.argos.page.ReadinessWaiter;
import com.aresstack.argos.page.SnapshotReader;
import com.aresstack.argos.network.FirefoxNetworkPrefs;
import com.aresstack.argos.network.NetworkSettings;
import com.aresstack.argos.network.Socks5Proxy;
import com.aresstack.argos.network.UpstreamProxy;
import com.aresstack.argos.fetch.BrowserContextAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Owns one persistent Camoufox browser and serializes every Playwright API call
 * onto a single driver thread. Never attaches to an existing user's browser.
 */
public final class CamoufoxBrowser implements BrowserPagePort, BrowserContextAccess, AutoCloseable {
    private static final int MAX_NETWORK_EVENTS = 200;
    /** Source documents above this size are not pulled through the protocol for a snapshot. */
    private static final long MAX_SOURCE_BYTES = 8_000_000;
    private static final int PROBE_TIMEOUT_MS = 2_000;
    private final ArgosConfig config;
    private final ExecutorService driver = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().name("argos-camoufox-driver").unstarted(runnable));
    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<Map<String, Object>> network = new ArrayDeque<>();
    private final BrowserTabs tabs = new BrowserTabs();
    private final Map<Page, PageControls> controlSnapshots = new IdentityHashMap<>();
    private final RealInput input;
    private final SnapshotReader snapshots = new SnapshotReader();
    /** Per-tab protocol observations: in-flight requests and the top-level document of each tab. */
    private final Map<Page, TabDocument> documents = new IdentityHashMap<>();
    private Playwright playwright;
    private Socks5Proxy proxy;
    private BrowserContext context;
    /** True while a download or batch runs its own background tabs on the driver thread. */
    private boolean backgroundWork;

    public CamoufoxBrowser(ArgosConfig config) {
        this(config, HumanInputSettings.fromEnvironment());
    }

    CamoufoxBrowser(ArgosConfig config, HumanInputSettings humanInput) {
        this.config = config;
        this.input = new RealInput(humanInput);
    }

    public <T> T onBrowser(Callable<T> action) {
        return onBrowser(action, 90);
    }

    /** Like {@link #onBrowser(Callable)} with a longer budget for multi-tab work such as batches. */
    public <T> T onBrowser(Callable<T> action, int timeoutSeconds) {
        try {
            Future<T> result = driver.submit(action);
            return result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Browser task interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            // Report the browser task's own message, not the executor's wrapper text.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Camoufox operation failed: " + cause.getMessage(), cause);
        } catch (Exception e) {
            throw new IllegalStateException("Camoufox operation failed: " + e.getMessage(), e);
        }
    }

    private Page activePage() throws Exception {
        Optional<Page> current = tabs.current();
        if (current.isPresent()) return current.get();
        ensureContext();
        Page first = context.pages().stream().filter(p -> !p.isClosed()).findFirst()
                .orElseGet(context::newPage);
        return tabs.select(adopt(first));
    }

    private void ensureContext() throws Exception {
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
            fingerprintWindow(variables);
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
            // Playwright's BiDi layer misses history traversals restored from the back-forward cache
            // (page.url() stays stale, goBack never settles). The official launch.json may still override it.
            prefs.putIfAbsent("browser.sessionhistory.max_total_viewers", 0);
            NetworkSettings network = config.network();
            if (network.strictMode()) {
                network = withLaunchProxy(network, launch.path("proxy"));
                for (String key : prefs.keySet()) {
                    if (FirefoxNetworkPrefs.ownedPrefixes().stream().anyMatch(key::startsWith)) {
                        throw new IllegalArgumentException("launch.json sets " + key + ", but Argos owns the "
                                + "browser network settings. Configure a browser proxy as ARGOS_UPSTREAM_PROXY.");
                    }
                }
                // Fail closed: no proxy, no browser.
                proxy = Socks5Proxy.start(network, Socks5Proxy.SYSTEM_DNS);
                proxy.protectLoopbackPort(config.port());
                prefs.putAll(FirefoxNetworkPrefs.forLocalSocks(proxy.port()));
            } else {
                System.err.println("ARGOS_NETWORK_POLICY=off: Camoufox connects directly and can reach "
                        + "loopback and intranet services.");
            }
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
            try {
                playwright = Playwright.create();
                context = playwright.firefox().launchPersistentContext(config.profile(), options);
                if (proxy != null) verifyNetworkBoundary();
            } catch (Exception error) {
                shutDown();
                throw error;
            }
            // Tabs opened by websites (target=_blank, window.open) are adopted like our own.
            context.onPage(this::adopt);
        }
    }

    /**
     * The inner window size Camoufox gives the page ({@code window.innerWidth/innerHeight} of the fingerprint, which
     * is split over {@code CAMOU_CONFIG_1..n}). Real input scrolls and moves inside it without asking the page.
     */
    private void fingerprintWindow(JsonNode variables) {
        StringBuilder json = new StringBuilder();
        for (int part = 1; variables.has("CAMOU_CONFIG_" + part); part++) {
            json.append(variables.get("CAMOU_CONFIG_" + part).asText());
        }
        try {
            JsonNode fingerprint = mapper.readTree(json.toString());
            double width = fingerprint.path("window.innerWidth").asDouble(0);
            double height = fingerprint.path("window.innerHeight").asDouble(0);
            if (width > 0 && height > 0) input.viewport(width, height);
        } catch (Exception unreadable) {
            // Without it the viewport is read once from the page when the first click needs it.
        }
    }

    /** Registers a page as tab and records its network traffic. Idempotent. */
    private String adopt(Page page) {
        if (backgroundWork) return null;
        Optional<String> known = tabs.idOf(page);
        if (known.isPresent()) return known.get();
        String id = tabs.adopt(page);
        TabDocument tab = new TabDocument();
        documents.put(page, tab);
        // Protocol events only: observing them is invisible to the website.
        page.onRequest(request -> {
            if (isMainDocument(page, request)) tab.activity.reset();
            tab.activity.started();
            record("request", request.method(), request.url(), 0);
        });
        page.onResponse(response -> {
            if (isMainDocument(page, response.request())) {
                MainDocument document = new MainDocument(response);
                if (document.attachment()) tab.lastDownload = document;
                else tab.mainDocument = document;
            }
            record("response", response.request().method(), response.url(), response.status());
        });
        page.onRequestFinished(request -> tab.activity.ended());
        page.onRequestFailed(request -> {
            tab.activity.ended();
            record("failed", request.method(), request.url(), 0);
        });
        page.onClose(closed -> {
            tabs.forget(closed);
            controlSnapshots.remove(closed);
            documents.remove(closed);
        });
        return id;
    }

    private TabDocument document(Page page) {
        return documents.computeIfAbsent(page, unknown -> new TabDocument());
    }

    /** What the protocol told us about one tab: request activity and its top-level document response. */
    private static final class TabDocument {
        final NetworkActivity activity = new NetworkActivity(System::currentTimeMillis);
        MainDocument mainDocument;
        /** Last top-level response the browser saved as a download; it never replaces the rendered page. */
        MainDocument lastDownload;
    }

    private static boolean isMainDocument(Page owner, Request request) {
        try {
            return request.isNavigationRequest() && request.frame() == owner.mainFrame();
        } catch (RuntimeException workerOrDetachedFrame) {
            return false;
        }
    }

    /** Chains a proxy from Camoufox' launch.json, which Argos would otherwise drop under strict mode. */
    private static NetworkSettings withLaunchProxy(NetworkSettings network, JsonNode launchProxy) {
        if (!launchProxy.isObject() || !launchProxy.path("server").isTextual()) return network;
        UpstreamProxy fromLaunch = UpstreamProxy.parse(launchProxy.path("server").asText(),
                launchProxy.path("username").asText(null), launchProxy.path("password").asText(null));
        if (network.upstream() == null) return network.withUpstream(fromLaunch);
        if (!network.upstream().equals(fromLaunch)) {
            throw new IllegalArgumentException("launch.json proxy " + fromLaunch
                    + " differs from ARGOS_UPSTREAM_PROXY " + network.upstream());
        }
        return network;
    }

    /**
     * Start self-test in a throwaway tab: a loopback canary and a never-resolvable host must both arrive
     * at the proxy as refused connections, the canary must see no connection, and the repeat proves
     * Firefox does not fall back to direct connections after proxy refusals.
     */
    private void verifyNetworkBoundary() throws IOException {
        AtomicInteger direct = new AtomicInteger();
        try (ServerSocket canary = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> {
                while (!canary.isClosed()) {
                    try (Socket ignored = canary.accept()) {
                        direct.incrementAndGet();
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            int port = canary.getLocalPort();
            String loopback = "127.0.0.1:" + port;
            String probeHost = "argos-boundary-probe.invalid:" + port;
            proxy.protectLoopbackPort(port);
            Page probe = context.newPage();
            try {
                for (String target : List.of(loopback, probeHost, loopback)) {
                    try {
                        probe.navigate("http://" + target + "/", new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.COMMIT).setTimeout(15_000));
                    } catch (PlaywrightException refused) {
                        // Expected: the proxy refuses both targets.
                    }
                }
            } finally {
                probe.close();
                proxy.releaseLoopbackPort(port);
            }
            List<Object> seen = proxy.blockedEvents().stream().map(event -> event.get("url")).toList();
            proxy.discardBlockedEvents(loopback);
            proxy.discardBlockedEvents(probeHost);
            if (direct.get() > 0) {
                throw new IllegalStateException("Network boundary self-test failed: Camoufox reached a loopback "
                        + "port without the Argos proxy.");
            }
            if (!seen.contains(loopback)) {
                throw new IllegalStateException("Network boundary self-test failed: Camoufox did not send "
                        + "127.0.0.1 through the Argos proxy (network.proxy.allow_hijacking_localhost ignored?).");
            }
            if (!seen.contains(probeHost)) {
                throw new IllegalStateException("Network boundary self-test failed: Camoufox did not leave DNS "
                        + "to the Argos proxy (network.proxy.socks5_remote_dns ignored?).");
            }
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
            requireWebUrl(target);
            Page p = activePage();
            ReadinessPolicy policy = config.readiness();
            TabDocument tab = document(p);
            MainDocument previousDocument = tab.mainDocument;
            MainDocument previousDownload = tab.lastDownload;
            Response response;
            try {
                response = p.navigate(target, new Page.NavigateOptions()
                        .setWaitUntil(waitUntil(policy)).setTimeout(45_000));
            } catch (PlaywrightException error) {
                // Only a response of this navigation may turn the failure into a download.
                MainDocument download = tab.lastDownload != previousDownload ? tab.lastDownload
                        : tab.mainDocument != previousDocument ? tab.mainDocument : null;
                if (!isDownload(error, download)) throw error;
                tab.mainDocument = previousDocument; // the previous page stays rendered
                return downloadResult(target, download);
            }
            if (response != null && isMainDocument(p, response.request())) tab.mainDocument = new MainDocument(response);
            ReadinessReport readiness = settle(p, policy, null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", p.url());
            result.put("title", p.title());
            result.put("status", response == null ? 0 : response.status());
            result.put("documentType", documentType(p));
            result.put("contentType", contentType(p));
            result.put("readiness", readiness);
            return result;
        });
    }

    @Override
    public <T> T inBrowserContext(Function<BrowserContext, T> task, int timeoutSeconds) {
        return onBrowser(() -> {
            activePage();
            // Background tabs (downloads, batches) are opened and closed inside this task; they are
            // not the agent's tabs and must never become current.
            backgroundWork = true;
            try {
                return task.apply(context);
            } finally {
                backgroundWork = false;
            }
        }, timeoutSeconds);
    }

    @Override
    public String currentUrl() {
        return onBrowser(() -> tabs.current().map(Page::url).orElse(""));
    }


    static void requireWebUrl(String target) {
        java.net.URI destination = java.net.URI.create(target);
        if (destination.getScheme() == null
                || !(destination.getScheme().equalsIgnoreCase("http")
                || destination.getScheme().equalsIgnoreCase("https"))
                || destination.getHost() == null) {
            throw new IllegalArgumentException("Navigation requires an absolute http(s) URL");
        }
    }

    /**
     * Waits until the configured readiness conditions hold, optionally also for a
     * visible element ({@code selector}) and/or visible {@code text}.
     */
    public Map<String, Object> waitFor(String selector, String text, int timeoutMillis) {
        return onBrowser(() -> {
            Page p = activePage();
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

    // BrowserPagePort: a plain browser navigation plus exactly one read of the serialized
    // DOM in Playwright's isolated utility world; title, text and links are derived in Java.
    @Override
    public PageCapture open(String url, String referer) {
        return onBrowser(() -> {
            Page p = activePage();
            Page.NavigateOptions options = new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000);
            if (referer != null && !referer.isBlank()) options.setReferer(referer);
            p.navigate(url, options);
            return capture(p);
        });
    }

    @Override
    public PageCapture current() {
        return onBrowser(() -> capture(activePage()));
    }

    @Override
    public PageCapture back() {
        return onBrowser(() -> {
            Page p = activePage();
            Response previous = p.goBack(new Page.GoBackOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000));
            return previous == null ? null : capture(p);
        });
    }

    @Override
    public void clear() {
        onBrowser(() -> {
            activePage().navigate("about:blank");
            return null;
        });
    }

    private PageCapture capture(Page p) {
        // Feeds, XML, JSON and plain text are read from the response body (protocol data, invisible to
        // the page); a binary document has no page text. Both use the classification of browser_snapshot.
        MainDocument main = document(p).mainDocument;
        if (main != null && main.url().equals(p.url())) {
            DocumentType type = DocumentType.classify(main.contentType(), main.url());
            if (type == DocumentType.BINARY) {
                return new PageCapture(p.url(), "", "Binary document (" + DocumentType.mimeType(main.contentType())
                        + "). It has no page text; use the document tools to download and read it.", "");
            }
            Optional<String> body = type.isSourceDocument() ? main.body() : Optional.empty();
            if (body.isPresent()) {
                String source = body.get();
                String text = switch (type) {
                    case JSON -> DocumentText.json(source);
                    case TEXT -> source;
                    default -> DocumentText.feed(source).orElseGet(() -> DocumentText.xml(source));
                };
                return new PageCapture(p.url(), "", text, "");
            }
        }
        // Every in-page read is a protocol evaluation (with user activation, see
        // playwright4j #37), so take one content() call and avoid title()/innerText()/count().
        return PageText.capture(p.url(), p.content());
    }

    public PageSnapshot snapshot(int maxCharacters) {
        return onBrowser(() -> {
            Page p = activePage();
            return snapshots.read(new PlaywrightPageSource(p, document(p).mainDocument), maxCharacters);
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
            return new PageSample(0, 0, false, targetVisible, document(p).activity.inflight(), document(p).activity.quietMillis());
        }
        int elements = p.locator("*").count();
        int textLength = visibleText(p).length();
        boolean loading = !policy.loadingSelectors().isEmpty()
                && p.locator(String.join(", ", policy.loadingSelectors())).filter(visible).count() > 0;
        return new PageSample(elements, textLength, loading, targetVisible, document(p).activity.inflight(), document(p).activity.quietMillis());
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
        DocumentType type = DocumentType.classify(contentType(p), p.url());
        return type == DocumentType.UNKNOWN ? DocumentType.HTML : type;
    }

    private String contentType(Page p) {
        MainDocument main = document(p).mainDocument;
        return main == null ? null : main.contentType();
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
        private Optional<String> body;

        MainDocument(Response response) {
            this.response = response;
            this.url = response.url();
            this.status = response.status();
            this.contentType = response.headerValue("content-type");
            String disposition = response.headerValue("content-disposition");
            this.attachment = disposition != null && disposition.toLowerCase(java.util.Locale.ROOT).startsWith("attachment");
        }

        String url() { return url; }
        int status() { return status; }
        String contentType() { return contentType; }
        boolean attachment() { return attachment; }

        /** Response bodies travel over the protocol; the page cannot observe this read. */
        Optional<String> body() {
            if (body != null) return body;
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
            input.click(p, p.locator(selector).first());
            settle(p);
            return Map.of("clicked", selector, "url", p.url());
        });
    }

    public Map<String, Object> fill(String selector, String text) {
        return onBrowser(() -> {
            Page p = activePage();
            input.replaceText(p, p.locator(selector).first(), text);
            return Map.of("filled", selector, "url", p.url());
        });
    }

    // ---- Tabs -------------------------------------------------------------------------------------------

    public List<Map<String, Object>> tabs() {
        return onBrowser(() -> {
            activePage();
            return tabList();
        });
    }

    private List<Map<String, Object>> tabList() {
        List<Map<String, Object>> list = new ArrayList<>();
        String current = tabs.currentId();
        tabs.all().forEach((id, tab) -> list.add(tabInfo(id, tab, id.equals(current))));
        return list;
    }

    private static Map<String, Object> tabInfo(String id, Page tab, boolean current) {
        return Map.of("tab", id, "current", current, "url", tab.url(), "title", safeTitle(tab));
    }

    private static String safeTitle(Page tab) {
        try {
            return tab.title();
        } catch (RuntimeException navigating) {
            return "";
        }
    }

    /** Opens a new tab, makes it current and loads {@code url} unless it is blank. */
    public Map<String, Object> newTab(String url) {
        return onBrowser(() -> {
            if (url != null && !url.isBlank()) requireWebUrl(url);
            activePage();
            Page created = context.newPage();
            String id = adopt(created);
            tabs.select(id);
            int status = 0;
            if (url != null && !url.isBlank()) {
                Response response = created.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000));
                status = response == null ? 0 : response.status();
            }
            Map<String, Object> result = new LinkedHashMap<>(tabInfo(id, created, true));
            result.put("status", status);
            return result;
        });
    }

    public Map<String, Object> selectTab(String id) {
        return onBrowser(() -> {
            activePage();
            Page selected = tabs.select(id);
            selected.bringToFront();
            return tabInfo(tabs.currentId(), selected, true);
        });
    }

    public Map<String, Object> closeTab(String id) {
        return onBrowser(() -> {
            activePage();
            Page closing = tabs.get(id);
            if (tabs.size() <= 1) {
                throw new IllegalArgumentException("The last tab stays open; navigate it instead.");
            }
            closing.close();
            tabs.forget(closing);
            return Map.of("closed", BrowserTabs.normalize(id), "current", tabs.currentId(), "tabs", tabList());
        });
    }

    // ---- History ----------------------------------------------------------------------------------------

    public Map<String, Object> historyBack() {
        return traverse("back");
    }

    public Map<String, Object> forward() {
        return traverse("forward");
    }

    public Map<String, Object> reload() {
        return traverse("reload");
    }

    /** History traversal via BiDi {@code browsingContext.traverseHistory} / {@code reload}; no page script. */
    private Map<String, Object> traverse(String direction) {
        return onBrowser(() -> {
            Page p = activePage();
            String before = p.url();
            Response response = null;
            boolean timedOut = false;
            try {
                // COMMIT, not DOMCONTENTLOADED: a page restored from the back-forward cache fires no new load.
                response = switch (direction) {
                    case "back" -> p.goBack(new Page.GoBackOptions()
                            .setWaitUntil(WaitUntilState.COMMIT).setTimeout(15_000));
                    case "forward" -> p.goForward(new Page.GoForwardOptions()
                            .setWaitUntil(WaitUntilState.COMMIT).setTimeout(15_000));
                    default -> p.reload(new Page.ReloadOptions()
                            .setWaitUntil(WaitUntilState.COMMIT).setTimeout(45_000));
                };
            } catch (TimeoutError notReported) {
                timedOut = true;
            }
            settle(p);
            boolean moved = direction.equals("reload") || response != null || !before.equals(p.url());
            if (!moved) {
                throw new IllegalStateException(timedOut ? "The tab did not move in its history."
                        : "No " + (direction.equals("back") ? "previous" : "next") + " page in this tab's history.");
            }
            controlSnapshots.remove(p);
            return Map.of("url", p.url(), "title", safeTitle(p),
                    "status", response == null ? 0 : response.status());
        });
    }

    // ---- Controls and semantic targets -------------------------------------------------------------------

    /** A fresh numbered control snapshot of the current tab, replacing the previous one. */
    public PageControls controls() {
        return onBrowser(() -> takeControlSnapshot(activePage()));
    }

    private PageControls takeControlSnapshot(Page p) {
        // AI-mode aria snapshot: computed in Playwright's isolated utility world, assigns aria refs.
        String yaml = p.ariaSnapshot(new Page.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI).setTimeout(15_000));
        AriaTree tree = AriaTree.parse(yaml);
        PageControls snapshot = PageControls.from(p.url(), tree);
        Set<String> secrets = new HashSet<>();
        for (PageControls.Control control : snapshot.all()) {
            if (!control.value().isEmpty() && PageControls.EDITABLE_ROLES.contains(control.role())
                    // An element handle, unlike getAttribute, dispatches no __playwright_mark_target__ event.
                    && Boolean.TRUE.equals(RealInput.onElement(refLocator(p, control.ref()),
                    "element => element.type === 'password'", null))) {
                secrets.add(control.ref());
            }
        }
        if (!secrets.isEmpty()) snapshot = snapshot.withHiddenValues(secrets);
        controlSnapshots.put(p, snapshot);
        return snapshot;
    }

    private static Locator refLocator(Page p, String ref) {
        return p.locator("aria-ref=" + ref);
    }

    /** Result of a click or fill on a resolved target. */
    public record Interaction(String target, String url, String title, String openedTab) { }

    public Interaction clickTarget(String target) {
        return onBrowser(() -> {
            Page p = activePage();
            Set<String> before = tabs.all().keySet();
            Resolved resolved = resolve(p, target);
            input.click(p, resolved.locator());
            settle(p);
            String opened = adoptOpenedTab(before);
            Page now = activePage();
            return new Interaction(resolved.description(), now.url(), safeTitle(now), opened);
        });
    }

    public Interaction fillTarget(String target, String text) {
        return onBrowser(() -> {
            Page p = activePage();
            Resolved resolved = resolve(p, target);
            input.replaceText(p, resolved.locator(), text);
            if (text.endsWith("\n")) settle(p);
            return new Interaction(resolved.description(), p.url(), safeTitle(p), null);
        });
    }

    private record Resolved(Locator locator, String description) { }

    /**
     * Numbers refer to the snapshot the model has seen; every other target is resolved against a fresh one.
     * A ref that no longer matches (the page re-rendered) is looked up again by role and name.
     */
    private Resolved resolve(Page p, String target) {
        PageControls known = controlSnapshots.get(p);
        boolean numbered = target != null && target.trim().matches("\\[?#?\\d{1,4}]?");
        if (numbered && known != null && !known.url().equals(p.url())) {
            throw new IllegalArgumentException("The page changed since the controls were listed; list them again.");
        }
        PageControls snapshot = numbered && known != null ? known : takeControlSnapshot(p);
        PageControls.Target resolved = snapshot.resolve(target);
        if (!resolved.hasRef()) return fallback(p, resolved);
        Locator locator = refLocator(p, resolved.ref());
        if (locator.count() != 1) {
            PageControls fresh = takeControlSnapshot(p);
            String again = resolved.name().isBlank() ? "ref=" + resolved.ref()
                    : "role=" + resolved.role() + " name=\"" + resolved.name().replace("\"", "\\\"") + "\"";
            resolved = fresh.resolve(again);
            locator = refLocator(p, resolved.ref());
            if (locator.count() != 1) throw new IllegalStateException("The element disappeared from the page.");
        }
        String name = resolved.name().isBlank() ? "" : " \"" + resolved.name() + "\"";
        return new Resolved(locator, resolved.role() + name);
    }

    /** Playwright's own selector engines; like aria refs they are evaluated in the isolated utility world. */
    private static Resolved fallback(Page p, PageControls.Target target) {
        String value = target.fallbackValue();
        Locator exact = switch (target.fallbackKind()) {
            case "css" -> p.locator(value);
            case "label" -> p.getByLabel(value, new Page.GetByLabelOptions().setExact(true));
            case "placeholder" -> p.getByPlaceholder(value, new Page.GetByPlaceholderOptions().setExact(true));
            default -> p.getByText(value, new Page.GetByTextOptions().setExact(true));
        };
        Locator locator = exact;
        if (exact.count() == 0 && !target.fallbackKind().equals("css")) {
            locator = switch (target.fallbackKind()) {
                case "label" -> p.getByLabel(value);
                case "placeholder" -> p.getByPlaceholder(value);
                default -> p.getByText(value);
            };
        }
        int count = locator.count();
        if (count == 0) {
            throw new IllegalArgumentException("Nothing on the page matches \"" + value
                    + "\"; list the controls to see what the page offers.");
        }
        if (count > 1) {
            throw new IllegalArgumentException(count + " elements match \"" + value
                    + "\"; use the number from the control list instead.");
        }
        return new Resolved(locator, target.fallbackKind() + " \"" + value + "\"");
    }

    /** If the last action opened a tab, it becomes current, as it would for a person. */
    private String adoptOpenedTab(Set<String> before) {
        for (Page candidate : context.pages()) adopt(candidate);
        List<String> opened = tabs.all().keySet().stream().filter(id -> !before.contains(id)).toList();
        if (opened.isEmpty()) return null;
        String newest = opened.get(opened.size() - 1);
        Page tab = tabs.select(newest);
        try {
            tab.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (TimeoutError slow) {
            // A slow popup is still reported; reading it later waits again.
        }
        return newest;
    }

    /** Gives a click time to start a navigation, then waits for the new document without long sleeps. */
    private static void settle(Page p) {
        p.waitForTimeout(250);
        try {
            p.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (TimeoutError slow) {
            // The page keeps loading; the caller still gets the current address.
        }
    }

    // ---- Structured extraction --------------------------------------------------------------------------

    /**
     * Extracts headings, links, tables, lists, metadata or JSON-LD from the current tab. Only Playwright's
     * page-level aria snapshot and {@code evaluateAll} reads are used; locator-level ariaSnapshot, innerHTML and
     * getAttribute would dispatch {@code __playwright_mark_target__} events into the page (playwright4j #38).
     */
    public Map<String, Object> extract(String kind, String scope) {
        String what = kind == null || kind.isBlank() ? "overview" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        if (!PageExtractor.KINDS.contains(what)) {
            throw new IllegalArgumentException("kind must be one of " + PageExtractor.KINDS);
        }
        return onBrowser(() -> {
            Page p = activePage();
            if (p.locator("body").count() == 0) {
                throw new IllegalStateException("browser_extract needs an HTML page; use browser_snapshot for "
                        + "XML, JSON or text documents.");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", p.url());
            result.put("title", safeTitle(p));
            result.put("kind", what);
            AriaTree tree = switch (what) {
                case "metadata", "jsonld" -> null;
                default -> AriaTree.parse(p.ariaSnapshot(new Page.AriaSnapshotOptions().setTimeout(15_000)))
                        .within(scope);
            };
            switch (what) {
                case "headings" -> result.put("headings", PageExtractor.headings(tree, 200));
                case "links" -> result.put("links", PageExtractor.links(tree, p.url(), PageExtractor.MAX_LINKS));
                case "tables" -> result.put("tables", PageExtractor.tables(tree));
                case "lists" -> result.put("lists", PageExtractor.lists(tree));
                case "metadata" -> result.putAll(metadataOf(p));
                case "jsonld" -> result.put("jsonld", jsonLdOf(p));
                default -> {
                    Map<String, Object> metadata = metadataOf(p);
                    result.put("lang", metadata.getOrDefault("lang", ""));
                    Object description = ((Map<?, ?>) metadata.get("meta")).get("description");
                    if (description != null) result.put("description", description);
                    result.put("headings", PageExtractor.headings(tree, 30));
                    result.put("counts", Map.of(
                            "links", PageExtractor.links(tree, p.url(), Integer.MAX_VALUE).size(),
                            "tables", PageExtractor.tables(tree).size(),
                            "lists", PageExtractor.lists(tree).size(),
                            "controls", tree.findAll(n -> PageControls.CONTROL_ROLES.contains(n.role())
                                    && !n.has("disabled")).size()));
                    result.put("jsonLdTypes", PageExtractor.jsonLdTypes(jsonLdOf(p)));
                }
            }
            return result;
        });
    }

    private static Map<String, Object> metadataOf(Page p) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<?> lang = (List<?>) p.locator("html").evaluateAll("els => els.map(e => e.getAttribute('lang') || '')");
        metadata.put("lang", lang.isEmpty() ? "" : String.valueOf(lang.get(0)));
        List<?> heads = (List<?>) p.locator("head").evaluateAll("els => els.map(e => e.innerHTML)");
        String headHtml = heads.isEmpty() ? "" : String.valueOf(heads.get(0));
        metadata.put("meta", PageExtractor.metadata(headHtml, p.url()));
        return metadata;
    }

    private static List<Object> jsonLdOf(Page p) {
        Locator scripts = p.locator("script[type='application/ld+json']");
        List<String> texts = scripts.count() == 0 ? List.of() : scripts.allTextContents();
        return PageExtractor.jsonLd(texts.size() > 20 ? texts.subList(0, 20) : texts, 40_000);
    }

    public Object evaluate(String expression) {
        return onBrowser(() -> activePage().evaluate(expression));
    }

    public byte[] screenshot() {
        return onBrowser(() -> activePage().screenshot(new Page.ScreenshotOptions()
                .setFullPage(false).setTimeout(15_000)));
    }

    public List<Map<String, Object>> networkEvents() {
        return onBrowser(() -> {
            if (proxy == null) return List.copyOf(network);
            // Refused connections never reach the page as requests; merge them in time order.
            List<Map<String, Object>> events = new ArrayList<>(network);
            events.addAll(proxy.blockedEvents());
            events.sort(Comparator.comparing(event -> Instant.parse(event.get("at").toString())));
            return List.copyOf(events.subList(Math.max(0, events.size() - MAX_NETWORK_EVENTS), events.size()));
        });
    }

    public Map<String, Object> status() {
        return onBrowser(() -> Map.of("started", context != null, "profile", config.profile().toString(),
                "url", tabs.current().map(Page::url).orElse(""),
                "tab", Optional.ofNullable(tabs.currentId()).orElse(""), "tabs", tabs.size(),
                "network", networkStatus()));
    }

    /** Serialized page HTML, read through Playwright's isolated utility world: the page sees no script. */
    public record HtmlPage(String url, String title, int status, String html) {
    }

    public HtmlPage loadHtml(String target) {
        return onBrowser(() -> {
            java.net.URI destination = java.net.URI.create(target);
            if (destination.getScheme() == null || destination.getHost() == null
                    || !(destination.getScheme().equalsIgnoreCase("http")
                    || destination.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Navigation requires an absolute http(s) URL");
            }
            Page p = activePage();
            Response response = p.navigate(target, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000));
            settleLoad(p);
            return new HtmlPage(p.url(), p.title(), response == null ? 0 : response.status(), p.content());
        });
    }

    public HtmlPage currentHtml() {
        return onBrowser(() -> {
            Page p = activePage();
            settleLoad(p);
            return new HtmlPage(p.url(), p.title(), 0, p.content());
        });
    }

    /**
     * Clicks the element's bounding-box centre with real pointer input (WebDriver BiDi input actions),
     * never with a page-visible element.click(). Returns false when the element has no box.
     */
    public boolean clickWithTrustedInput(String selector) {
        return onBrowser(() -> {
            Page p = activePage();
            // Measure only (count/boundingBox); no Locator.click or scrollIntoView, whose actionability
            // machinery dispatches page-observable marker events (playwright4j #38).
            Locator target = p.locator(selector).first();
            if (target.count() == 0) return false;
            BoundingBox box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(2_000));
            if (box != null && (box.y < 0 || box.y + box.height > 700)) {
                p.mouse().wheel(0, box.y - 300); // scroll like a user, then measure again
                p.waitForTimeout(400);
                box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(2_000));
            }
            if (box == null || box.width < 1 || box.height < 1) return false;
            double x = box.x + box.width / 2;
            double y = box.y + box.height / 2;
            p.mouse().move(x, y, new Mouse.MoveOptions().setSteps(12));
            p.mouse().click(x, y);
            p.waitForTimeout(800);
            settleLoad(p);
            return true;
        });
    }

    /** DOMContentLoaded is enough to read; give late resources a bounded chance, never a long sleep. */
    private static void settleLoad(Page p) {
        try {
            p.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(5_000));
        } catch (PlaywrightException slowSubresources) {
            // Reading proceeds with the DOM that is there.
        }
    }

    private Map<String, Object> networkStatus() {
        NetworkSettings network = config.network();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("policy", network.mode().name().toLowerCase(java.util.Locale.ROOT));
        status.put("intranetAllow", network.policy().rules());
        status.put("upstreamProxy", network.upstream() == null ? "" : network.upstream().toString());
        if (proxy != null) {
            status.put("proxyRunning", proxy.running());
            status.put("blocked", proxy.blockedEvents().size());
        }
        return status;
    }

    private void shutDown() {
        try {
            if (context != null) context.close();
            if (playwright != null) playwright.close();
        } finally {
            context = null;
            playwright = null;
            // The proxy goes last: until then the browser still has its only route through it.
            if (proxy != null) proxy.close();
            proxy = null;
        }
    }

    @Override
    public void close() {
        onBrowser(() -> {
            shutDown();
            return null;
        });
        driver.shutdown();
    }
}
