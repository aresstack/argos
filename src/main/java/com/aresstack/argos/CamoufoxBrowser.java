package com.aresstack.argos;

import com.aresstack.argos.network.FirefoxNetworkPrefs;
import com.aresstack.argos.network.NetworkSettings;
import com.aresstack.argos.network.Socks5Proxy;
import com.aresstack.argos.network.UpstreamProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns one persistent Camoufox browser and serializes every Playwright API call
 * onto a single driver thread. Never attaches to an existing user's browser.
 */
public final class CamoufoxBrowser implements AutoCloseable {
    private static final int MAX_NETWORK_EVENTS = 200;
    private final ArgosConfig config;
    private final ExecutorService driver = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().name("argos-camoufox-driver").unstarted(runnable));
    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<Map<String, Object>> network = new ArrayDeque<>();
    private Playwright playwright;
    private Socks5Proxy proxy;
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
        }
        page = context.pages().stream().filter(p -> !p.isClosed()).findFirst()
                .orElseGet(context::newPage);
        page.onRequest(request -> record("request", request.method(), request.url(), 0));
        page.onResponse(response -> record("response", response.request().method(), response.url(), response.status()));
        page.onRequestFailed(request -> record("failed", request.method(), request.url(), 0));
        return page;
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
            java.net.URI destination = java.net.URI.create(target);
            if (!(destination.getScheme().equalsIgnoreCase("http")
                    || destination.getScheme().equalsIgnoreCase("https"))
                    || destination.getHost() == null) {
                throw new IllegalArgumentException("Navigation requires an absolute http(s) URL");
            }
            Page p = activePage();
            Response response = p.navigate(target, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000));
            return Map.of("url", p.url(), "title", p.title(),
                    "status", response == null ? 0 : response.status());
        });
    }

    public Map<String, Object> snapshot(int maxCharacters) {
        return onBrowser(() -> {
            Page p = activePage();
            int limit = Math.min(60_000, Math.max(1_000, maxCharacters));
            String text = p.locator("body").innerText();
            if (text.length() > limit) text = text.substring(0, limit) + "\n[TRUNCATED]";
            return Map.of("url", p.url(), "title", p.title(), "text", text);
        });
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
                "url", page == null || page.isClosed() ? "" : page.url(), "network", networkStatus()));
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
