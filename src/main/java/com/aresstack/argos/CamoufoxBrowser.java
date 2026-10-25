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
        }
        Page current = context.pages().stream().filter(p -> !p.isClosed()).findFirst()
                .orElseGet(context::newPage);
        page = current;
        mainDocument = null;
        lastDownload = null;
        activity.reset();
        // Protocol events only: observing them is invisible to the website.
        current.onRequest(request -> {
            if (isMainDocument(current, request)) activity.reset();
            activity.started();
            record("request", request.method(), request.url(), 0);
        });
        current.onResponse(response -> {
            if (isMainDocument(current, response.request())) {
                MainDocument document = new MainDocument(response);
                if (document.attachment()) lastDownload = document;
                else mainDocument = document;
            }
            record("response", response.request().method(), response.url(), response.status());
        });
        current.onRequestFinished(request -> activity.ended());
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
                return downloadResult(target, download);
            }
            if (response != null && isMainDocument(p, response.request())) mainDocument = new MainDocument(response);
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
        int elements = p.locator("*").count();
        int textLength = visibleText(p).length();
        boolean loading = !policy.loadingSelectors().isEmpty()
                && p.locator(String.join(", ", policy.loadingSelectors())).filter(visible).count() > 0;
        boolean targetVisible = target == null || target.filter(visible).count() > 0;
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
        DocumentType type = DocumentType.classify(contentType(), p.url());
        return type == DocumentType.UNKNOWN ? DocumentType.HTML : type;
    }

    private String contentType() {
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
            p.locator(selector).first().click();
            return Map.of("clicked", selector, "url", p.url());
        });
    }

    public Map<String, Object> fill(String selector, String text) {
        return onBrowser(() -> {
            Page p = activePage();
            p.locator(selector).first().fill(text);
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
                "url", page == null || page.isClosed() ? "" : page.url()));
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
