package com.aresstack.argos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
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
    private final ArgosConfig config;
    private final ExecutorService driver = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().name("argos-camoufox-driver").unstarted(runnable));
    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<Map<String, Object>> network = new ArrayDeque<>();
    private final BrowserTabs tabs = new BrowserTabs();
    private final Map<Page, PageControls> controlSnapshots = new IdentityHashMap<>();
    private final RealInput input = new RealInput(new SplittableRandom());
    private Playwright playwright;
    private BrowserContext context;

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
            // Tabs opened by websites (target=_blank, window.open) are adopted like our own.
            context.onPage(this::adopt);
        }
    }

    /** Registers a page as tab and records its network traffic. Idempotent. */
    private String adopt(Page page) {
        Optional<String> known = tabs.idOf(page);
        if (known.isPresent()) return known.get();
        String id = tabs.adopt(page);
        page.onRequest(request -> record("request", request.method(), request.url(), 0));
        page.onResponse(response -> record("response", response.request().method(), response.url(), response.status()));
        page.onRequestFailed(request -> record("failed", request.method(), request.url(), 0));
        page.onClose(closed -> {
            tabs.forget(closed);
            controlSnapshots.remove(closed);
        });
        return id;
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
            Response response = p.navigate(target, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000));
            return Map.of("url", p.url(), "title", p.title(),
                    "status", response == null ? 0 : response.status());
        });
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

    public Map<String, Object> back() {
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
        return onBrowser(() -> List.copyOf(network));
    }

    public Map<String, Object> status() {
        return onBrowser(() -> Map.of("started", context != null, "profile", config.profile().toString(),
                "url", tabs.current().map(Page::url).orElse(""),
                "tab", Optional.ofNullable(tabs.currentId()).orElse(""), "tabs", tabs.size()));
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
