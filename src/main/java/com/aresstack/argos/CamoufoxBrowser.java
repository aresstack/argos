package com.aresstack.argos;

import com.aresstack.argos.web.BrowserPagePort;
import com.aresstack.argos.web.PageCapture;
import com.aresstack.argos.web.PageText;
import com.aresstack.argos.web.UrlPolicy;
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
import com.aresstack.argos.network.HostResolver;
import com.aresstack.argos.network.NetworkSettings;
import com.aresstack.argos.network.Socks5Proxy;
import com.aresstack.argos.network.UpstreamProxy;
import com.aresstack.argos.fetch.BrowserContextAccess;
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
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.aresstack.argos.network.NetworkRecorder;
import com.aresstack.argos.network.SecretMasker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Owns one persistent Camoufox browser and serializes every Playwright API call
 * onto a single driver thread. Never attaches to an existing user's browser.
 */
public final class CamoufoxBrowser implements BrowserPagePort, BrowserContextAccess, AutoCloseable {
    /** Source documents above this size are not pulled through the protocol for a snapshot. */
    private static final long MAX_SOURCE_BYTES = 8_000_000;
    private static final long SAVED_DOCUMENT_WAIT_NANOS = 5_000_000_000L;
    /** Requests whose transfer ended, from context-level protocol events (driver thread); by identity, weak. */
    private final Set<Request> finishedRequests = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Request> failedRequests = Collections.newSetFromMap(new WeakHashMap<>());
    private static final int PROBE_TIMEOUT_MS = 2_000;
    private final ArgosConfig config;
    private final ExecutorService driver = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().name("argos-camoufox-driver").unstarted(runnable));
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretMasker masker = new SecretMasker();
    private final NetworkRecorder network;
    private final BrowserTabs tabs = new BrowserTabs();
    private final Map<Page, PageControls> controlSnapshots = new IdentityHashMap<>();
    private final RealInput input;
    private final UrlPolicy urlPolicy;
    private final SnapshotReader snapshots = new SnapshotReader();
    /** Per-tab protocol observations: in-flight requests and the top-level document of each tab. */
    private final Map<Page, TabDocument> documents = new IdentityHashMap<>();
    private volatile Playwright playwright;
    /** Child processes Playwright started (its driver; Camoufox runs below it), for stopping a stalled browser. */
    private final List<ProcessHandle> driverProcesses = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** While {@code Playwright.create()} runs: the child processes that existed before it. */
    private volatile Set<Long> launchingSince;
    private volatile boolean restartRequested;
    /** Counted down once the stopped browser has exited (or was killed). */
    private volatile CountDownLatch browserStopped;
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
        this.urlPolicy = UrlPolicy.from(config.network());
        this.network = new NetworkRecorder(masker, config.networkBodies());
    }

    public <T> T onBrowser(Callable<T> action) {
        return onBrowser(action, 90);
    }

    /** Like {@link #onBrowser(Callable)} with a longer budget for multi-tab work such as batches. */
    public <T> T onBrowser(Callable<T> action, int timeoutSeconds) {
        AtomicLong startedAt = new AtomicLong(); // 0 while the task waits in the queue
        // Whoever claims first decides: the driver thread runs the action, or the caller gives up ("busy").
        AtomicBoolean claimed = new AtomicBoolean();
        Future<T> result = driver.submit(() -> {
            if (restartRequested) resetAfterStall();
            startedAt.set(System.nanoTime());
            if (!claimed.compareAndSet(false, true)) return null; // the caller was already told nothing was done
            try {
                return action.call();
            } finally {
                // Outside Playwright event handlers, so protocol calls are safe here.
                if (context != null) network.captureQueuedBodies();
            }
        });
        long budget = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long queuedUntil = System.nanoTime() + budget;
        try {
            while (true) {
                long started = startedAt.get();
                long wait = (started == 0 ? queuedUntil : started + budget) - System.nanoTime();
                try {
                    return result.get(Math.max(1, wait), TimeUnit.NANOSECONDS);
                } catch (TimeoutException late) {
                    started = startedAt.get();
                    if (started == 0 && claimed.compareAndSet(false, true)) {
                        // Only waited behind another task (a batch, say): no reason to touch the browser.
                        result.cancel(false);
                        throw busy(timeoutSeconds);
                    }
                    started = startedAt.get(); // set before the driver thread claims the task
                    if (result.isDone() || System.nanoTime() - started < budget) continue;
                    recoverFromStall(result);
                    throw new BrowserStalled("Camoufox did not answer within " + timeoutSeconds + " s. The task"
                            + " was stopped and the browser is started again with the next call.", late);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The caller gave up (the MCP client cancelled, say): a queued action never runs, a started one keeps
            // its deadline.
            if (claimed.compareAndSet(false, true)) result.cancel(false);
            else watchWithoutCaller(result, startedAt.get() + budget);
            throw new IllegalStateException("Browser task interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            // Report the browser task's own message, not the executor's wrapper text.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // Playwright errors can quote URLs with tokens or credentials; never pass them through raw.
            String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : masker.text(cause.getMessage());
            throw new IllegalStateException("Camoufox operation failed: " + detail, cause);
        } catch (java.util.concurrent.CancellationException e) {
            throw busy(timeoutSeconds);
        }
    }

    /** Recovers a started task whose caller stopped waiting, should it miss its deadline. */
    private void watchWithoutCaller(Future<?> running, long deadline) {
        Thread.ofVirtual().name("argos-browser-watch").start(() -> {
            try {
                running.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException late) {
                if (!running.isDone()) recoverFromStall(running);
            } catch (Exception finishedOrInterrupted) {
                // Done one way or another.
            }
        });
    }

    /** A browser call that missed its deadline after it started; the browser is restarted. */
    static final class BrowserStalled extends IllegalStateException {
        BrowserStalled(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static IllegalStateException busy(int timeoutSeconds) {
        return new IllegalStateException("The browser was busy with another task for " + timeoutSeconds
                + " s; nothing was done. Try again.");
    }

    /**
     * A protocol call that never answers would block the single driver thread, and every later call behind it.
     * The stalled task is interrupted, and a watchdog thread closes Playwright's connection: every later protocol
     * call of that task then fails at once ("Playwright connection closed") instead of waiting again, and the
     * driver process ends, taking Camoufox with it. The next task on the driver thread forgets the session (the
     * persistent profile keeps cookies and logins); the next call starts the browser again.
     */
    private void recoverFromStall(Future<?> stuck) {
        Playwright stalled = playwright;
        List<ProcessHandle> driverProcesses = List.copyOf(this.driverProcesses);
        Set<Long> launching = launchingSince;
        // Latch, then flag, then stopper: the stopper can end the stalled task, and the next task must already
        // see the flag then, and wait for this latch.
        CountDownLatch stopped = new CountDownLatch(1);
        browserStopped = stopped;
        restartRequested = true;
        Thread.ofPlatform().name("argos-browser-stop").daemon(true).start(() -> {
            try {
                if (stalled != null) stalled.close(); // waits up to 30 s for the driver to exit
            } catch (RuntimeException alreadyBroken) {
                // The connection is gone either way.
            } finally {
                destroyTree(driverProcesses);
                // Stalled inside Playwright.create(): its driver is not recorded yet.
                if (launching != null) destroyTree(childrenSince(launching));
                stopped.countDown();
            }
        });
        stuck.cancel(true);
        driver.submit(() -> {
            if (restartRequested) resetAfterStall();
        });
    }

    /** On the driver thread, before anything else runs: waits for the stopped browser and forgets its session. */
    private void resetAfterStall() {
        restartRequested = false;
        System.err.println("Argos: a browser call stalled; restarting Camoufox.");
        CountDownLatch stopping = browserStopped;
        if (stopping != null) {
            try {
                stopping.await(40, TimeUnit.SECONDS); // the profile stays locked until the old browser has exited
            } catch (InterruptedException interrupted) {
                // Interrupted by a later stall: go on with the reset.
            }
        }
        context = null;
        playwright = null;
        driverProcesses.clear();
        launchingSince = null;
        if (proxy != null) proxy.close();
        proxy = null;
        tabs.clear();
        documents.clear();
        controlSnapshots.clear();
        finishedRequests.clear();
        failedRequests.clear();
    }

    /** Child processes of Argos that were not running at {@code before}. */
    private static List<ProcessHandle> childrenSince(Set<Long> before) {
        return ProcessHandle.current().children().filter(child -> !before.contains(child.pid())).toList();
    }

    private static void destroyTree(List<ProcessHandle> processes) {
        for (ProcessHandle process : processes) {
            if (!process.isAlive()) continue;
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
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
                proxy = Socks5Proxy.start(network, HostResolver.SYSTEM);
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
            Set<Long> before = new HashSet<>();
            ProcessHandle.current().children().forEach(child -> before.add(child.pid()));
            launchingSince = before;
            try {
                playwright = Playwright.create();
                driverProcesses.addAll(childrenSince(before));
                launchingSince = null;
                context = playwright.firefox().launchPersistentContext(config.profile(), options);
                if (proxy != null) verifyNetworkBoundary();
            } catch (Exception error) {
                Set<Long> launching = launchingSince;
                launchingSince = null;
                shutDown();
                // Playwright.create() failed half-way (interrupted, say): nobody else stops the driver it started.
                if (launching != null) destroyTree(childrenSince(launching));
                throw error;
            }
            probeUnicode();
            // Tabs opened by websites (target=_blank, window.open) are adopted like our own.
            context.onPage(this::adopt);
            // Protocol events: when a download's transfer ended (its request may not belong to the tab any more).
            context.onRequestFinished(finishedRequests::add);
            context.onRequestFailed(failedRequests::add);
        }
    }

    /** How umlauts and other non-US characters are typed, as probed at browser start (UNKNOWN before). */
    RealInput.UnicodeKeys unicodeInput() {
        return input.unicodeKeys();
    }

    /** Probes real Unicode keys on a blank tab before any website is opened. */
    private void probeUnicode() {
        Optional<Page> blank = context.pages().stream()
                .filter(p -> !p.isClosed() && "about:blank".equals(p.url())).findFirst();
        Page probe = blank.orElseGet(context::newPage);
        try {
            input.probeUnicode(probe);
        } finally {
            if (blank.isEmpty()) probe.close();
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
            if (isMainDocument(page, request)) {
                tab.activity.reset();
                tab.navigations++;
                tab.saved = null; // the tab loads a new document
            }
            tab.activity.started();
        });
        page.onDownload(download -> tab.download = download);
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) tab.saved = null; // also same-document navigations
        });
        page.onResponse(response -> {
            if (isMainDocument(page, response.request())) {
                MainDocument document = new MainDocument(response);
                if (document.attachment()) tab.lastDownload = document;
                else tab.mainDocument = document;
            }
        });
        page.onRequestFinished(request -> {
            tab.activity.ended();
            MainDocument main = tab.mainDocument;
            if (main != null && main.isFor(request)) main.finished = true;
        });
        page.onRequestFailed(request -> tab.activity.ended());
        // Protocol-level BiDi network events only; nothing is injected into the page.
        network.attach(page);
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
        Download download;
        /** Top-level navigation requests seen in this tab. */
        int navigations;
        /** A feed, XML, JSON or text document Firefox saved instead of rendering, read from the saved file. */
        SavedDocument saved;
    }

    /** A source document the browser downloaded; current for its tab until the tab loads another document. */
    private record SavedDocument(String url, int status, String contentType, String text) { }

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

    public Map<String, Object> navigate(String target) {
        requireWebUrl(target);
        return onBrowser(() -> navigateHere(activePage(), target));
    }

    /** {@link #navigate} on the driver thread. */
    private Map<String, Object> navigateHere(Page p, String target) throws Exception {
        {
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
                Optional<SavedDocument> saved = savedSourceDocument(p, tab, download);
                if (saved.isPresent()) {
                    tab.saved = saved.get();
                    return savedResult(saved.get());
                }
                return downloadResult(target, download);
            }
            // Keep the listener's instance: it may already carry the requestfinished flag.
            if (response != null && isMainDocument(p, response.request())
                    && (tab.mainDocument == null || !tab.mainDocument.isFor(response.request()))) {
                tab.mainDocument = new MainDocument(response);
            }
            ReadinessReport readiness = settle(p, policy, null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", p.url());
            result.put("title", p.title());
            result.put("status", response == null ? 0 : response.status());
            result.put("documentType", documentType(p));
            result.put("contentType", contentType(p));
            result.put("readiness", readiness);
            return result;
        }
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
        return onBrowser(() -> tabs.current().map(this::shownUrl).orElse(""));
    }

    /** The address of the document a tab shows: a saved feed, else the rendered page. */
    private String shownUrl(Page p) {
        SavedDocument saved = document(p).saved;
        return saved != null ? saved.url() : p.url();
    }

    /**
     * Early, informative check with the central network policy (the same {@code NetworkPolicy} the proxy
     * enforces), so a refused target fails with a clear message before the browser loads anything. It is
     * not the boundary: redirects and subresources are refused by the proxy, in every tool profile.
     */
    void requireWebUrl(String target) {
        urlPolicy.check(target);
    }

    /**
     * Waits until the configured readiness conditions hold, optionally also for a
     * visible element ({@code selector}) and/or visible {@code text}.
     */
    public Map<String, Object> waitFor(String selector, String text, int timeoutMillis) {
        return onBrowser(() -> {
            Page p = activePage();
            SavedDocument saved = document(p).saved;
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
            return captureWhenLoaded(p);
        });
    }

    @Override
    public PageCapture current() {
        return onBrowser(() -> captureWhenLoaded(activePage()));
    }

    private static final long LOADING_GRACE_MILLIS = 8_000;
    private static final Pattern LOADING_HINT = Pattern.compile(
            "(?iu)\\b(loading|wird geladen|lädt|bitte warten|please wait)\\b");

    /**
     * A page that still says it is loading while its own requests are in flight (a single-page app waiting
     * for its data) is read again once those requests finished, at most {@link #LOADING_GRACE_MILLIS} later.
     * Only protocol events are watched; the second read happens only for such pages. Small models otherwise
     * read "Loading…" and give up (Phase 7 evaluation, task dynamic-page; ported from #13 commit 3701fd0).
     */
    private PageCapture captureWhenLoaded(Page p) {
        PageCapture capture = capture(p);
        if (!LOADING_HINT.matcher(capture.text()).find()) return capture;
        NetworkActivity activity = document(p).activity;
        if (activity.inflight() == 0) return capture;
        long deadline = System.currentTimeMillis() + LOADING_GRACE_MILLIS;
        while (activity.inflight() > 0 && System.currentTimeMillis() < deadline) p.waitForTimeout(150);
        // Rendering the arrived data takes a moment after the response.
        p.waitForTimeout(200);
        return capture(p);
    }

    @Override
    public PageCapture back() {
        return onBrowser(() -> {
            Page p = activePage();
            if (document(p).saved != null) {
                // As in traverse("back"): the saved feed is no history entry of its own, back returns to the
                // page the tab shows behind it.
                leaveSavedDocument(p);
                controlSnapshots.remove(p);
                return capture(p);
            }
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
        SavedDocument saved = document(p).saved;
        if (saved != null) return new PageCapture(saved.url(), "", sourceText(saved.contentType(), saved.url(),
                saved.text()), "", saved.status());
        // The status of the document's own response (a protocol event Argos already records for the
        // document type and readiness); 0 when the shown document has no recorded response, e.g. a
        // history move the browser served from its cache. web_* report it to the agent for error pages.
        MainDocument main = document(p).mainDocument;
        boolean shown = main != null && main.url().equals(p.url());
        int status = shown ? main.status() : 0;
        if (shown) {
            DocumentType type = DocumentType.classify(main.contentType(), main.url());
            if (type == DocumentType.BINARY) {
                return new PageCapture(p.url(), "", "Binary document (" + DocumentType.mimeType(main.contentType())
                        + "). It has no page text; use the document tools to download and read it.", "", status);
            }
            Optional<String> body = type.isSourceDocument() ? main.body() : Optional.empty();
            if (body.isPresent()) return new PageCapture(p.url(), "", sourceText(main.contentType(), main.url(),
                    body.get()), "", status);
        }
        // Every in-page read is a protocol evaluation (with user activation, see
        // playwright4j #37), so take one content() call and avoid title()/innerText()/count().
        return PageText.capture(p.url(), p.content(), status);
    }

    private static String sourceText(String contentType, String url, String source) {
        return switch (DocumentType.classify(contentType, url)) {
            case JSON -> DocumentText.json(source);
            case TEXT -> DocumentText.plainText(source);
            default -> DocumentText.feed(source).orElseGet(() -> DocumentText.xml(source));
        };
    }

    public PageSnapshot snapshot(int maxCharacters) {
        return onBrowser(() -> {
            Page p = activePage();
            SavedDocument saved = document(p).saved;
            if (saved != null) return snapshots.read(new SavedPageSource(saved), maxCharacters);
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
        SavedDocument saved = document(p).saved;
        DocumentType type = saved != null ? DocumentType.classify(saved.contentType(), saved.url())
                : DocumentType.classify(contentType(p), p.url());
        return type == DocumentType.UNKNOWN ? DocumentType.HTML : type;
    }

    private String contentType(Page p) {
        SavedDocument saved = document(p).saved;
        if (saved != null) return saved.contentType();
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

    /**
     * Firefox saves feeds ({@code application/rss+xml}, {@code application/atom+xml}) and some other source
     * documents as downloads instead of rendering them. Their text is read from the saved file: nothing of
     * this reaches the page. An attachment ({@code Content-Disposition}) stays a download.
     */
    private Optional<SavedDocument> savedSourceDocument(Page p, TabDocument tab, MainDocument response) {
        if (response == null || response.attachment()) return Optional.empty();
        DocumentType type = DocumentType.classify(response.contentType(), response.url());
        if (!type.isSourceDocument()) return Optional.empty();
        Long length = response.contentLength();
        if (length != null && length > MAX_SOURCE_BYTES) return Optional.empty();
        // Never wait for an unfinished download on the driver thread (Download.path() has no deadline): only a
        // download the browser reports finished within a few seconds is read, anything else stays a download.
        long deadline = System.nanoTime() + SAVED_DOCUMENT_WAIT_NANOS;
        String url = response.url();
        if (!pumpUntil(p, () -> tab.download != null && url.equals(tab.download.url()), deadline)) {
            return Optional.empty();
        }
        Download download = tab.download;
        tab.download = null;
        // This response's own request: an earlier transfer of the same URL may end in the meantime.
        Request request = response.request();
        if (!pumpUntil(p, () -> finishedRequests.contains(request) || failedRequests.contains(request), deadline)
                || failedRequests.contains(request)) {
            return Optional.empty(); // still loading or failed: the browser keeps it as a download
        }
        try {
            java.nio.file.Path file = download.path(); // the transfer has ended, so this returns promptly
            try {
                if (file == null || Files.size(file) > MAX_SOURCE_BYTES) return Optional.empty();
                String text = decode(Files.readAllBytes(file), response.contentType(), type);
                return Optional.of(new SavedDocument(url, response.status(), response.contentType(), text));
            } finally {
                download.delete();
            }
        } catch (IOException | RuntimeException unavailable) {
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
    private static Map<String, Object> savedWaitResult(SavedDocument saved, String selector, String text) {
        Map<String, Object> result = savedResult(saved);
        boolean bySelector = selector != null && !selector.isBlank();
        if (bySelector || (text != null && !text.isBlank())) {
            boolean found = !bySelector && looseText(sourceText(saved.contentType(), saved.url(), saved.text()))
                    .contains(looseText(text));
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
            input.click(p, p.locator(selector).first());
            leaveSavedDocument(p);
            settle(p);
            return Map.of("clicked", selector, "url", p.url());
        });
    }

    public Map<String, Object> fill(String selector, String text) {
        return onBrowser(() -> {
            Page p = activePage();
            input.replaceText(p, p.locator(selector).first(), text);
            leaveSavedDocument(p);
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

    private Map<String, Object> tabInfo(String id, Page tab, boolean current) {
        boolean saved = document(tab).saved != null; // a saved feed has no title, as in savedResult
        return Map.of("tab", id, "current", current, "url", shownUrl(tab), "title", saved ? "" : safeTitle(tab));
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
        if (url != null && !url.isBlank()) requireWebUrl(url);
        return onBrowser(() -> {
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

    /**
     * Controls, clicks, typing and history moves act on the rendered page: once one of them succeeded, a saved feed
     * shown instead of it (see {@link #savedSourceDocument}) is left, so every tool reports the same document. A
     * failed call leaves it in place.
     */
    private void leaveSavedDocument(Page p) {
        document(p).saved = null;
    }

    /** History traversal via BiDi {@code browsingContext.traverseHistory} / {@code reload}; no page script. */
    private Map<String, Object> traverse(String direction) {
        return onBrowser(() -> {
            Page p = activePage();
            SavedDocument saved = document(p).saved;
            if (saved != null && !direction.equals("forward")) {
                // The saved feed is no history entry of its own: back returns to the page shown before it,
                // reload loads the feed again. Forward leaves it only once the tab has moved.
                leaveSavedDocument(p);
                if (direction.equals("reload")) return navigateHere(p, saved.url());
                if (direction.equals("back")) {
                    MainDocument main = document(p).mainDocument;
                    controlSnapshots.remove(p);
                    return Map.of("url", p.url(), "title", safeTitle(p), "status", main == null ? 0 : main.status());
                }
            }
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
            leaveSavedDocument(p);
            controlSnapshots.remove(p);
            return Map.of("url", p.url(), "title", safeTitle(p),
                    "status", response == null ? 0 : response.status());
        });
    }

    // ---- Controls and semantic targets -------------------------------------------------------------------

    /** A fresh numbered control snapshot of the current tab, replacing the previous one. */
    public PageControls controls() {
        return onBrowser(() -> {
            Page p = activePage();
            PageControls snapshot = takeControlSnapshot(p);
            leaveSavedDocument(p);
            return snapshot;
        });
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
            leaveSavedDocument(p);
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
            leaveSavedDocument(p);
            if (RealInput.lineEnds(text).endsWith("\n")) settle(p);
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
            if (document(p).saved != null || p.locator("body").count() == 0) {
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
        return networkEvents(null);
    }

    public List<Map<String, Object>> networkEvents(String filter) {
        return onBrowser(() -> {
            List<Map<String, Object>> rows = network.summary(filter);
            if (proxy == null) return rows;
            // Refused connections never reach the page as requests; merge them in time order.
            String needle = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.ROOT);
            List<Map<String, Object>> events = new ArrayList<>(rows);
            for (Map<String, Object> blocked : proxy.blockedEvents()) {
                if (needle.isEmpty() || blocked.values().stream()
                        .anyMatch(value -> String.valueOf(value).toLowerCase(java.util.Locale.ROOT).contains(needle))) {
                    events.add(blocked);
                }
            }
            events.sort(Comparator.comparing(event -> Instant.parse(event.get("at").toString())));
            int max = NetworkRecorder.MAX_ENTRIES;
            return List.copyOf(events.subList(Math.max(0, events.size() - max), events.size()));
        });
    }

    public Map<String, Object> networkDetail(String id, boolean includeBody) {
        return onBrowser(() -> network.detail(id, includeBody));
    }

    public List<Map<String, Object>> networkJson(String filter, int limit) {
        return onBrowser(() -> network.jsonResponses(filter, limit));
    }

    public Map<String, Object> networkHar(int limit) {
        return onBrowser(() -> network.har(limit, ArgosTools.VERSION));
    }

    public Map<String, Object> clearNetwork() {
        return onBrowser(() -> {
            network.clear();
            return Map.of("cleared", true);
        });
    }

    public Map<String, Object> status() {
        return onBrowser(() -> Map.of("started", context != null, "profile", config.profile().toString(),
                "url", tabs.current().map(this::shownUrl).orElse(""),
                "tab", Optional.ofNullable(tabs.currentId()).orElse(""), "tabs", tabs.size(),
                "network", networkStatus()));
    }

    /** Serialized page HTML, read through Playwright's isolated utility world: the page sees no script. */
    public record HtmlPage(String url, String title, int status, String html) {
    }

    public HtmlPage loadHtml(String target) {
        requireWebUrl(target);
        return onBrowser(() -> {
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
     * Clicks the element with real, human pointer input (WebDriver BiDi input actions), never with a
     * page-visible element.click(). Returns false when the element is missing or cannot be reached.
     */
    public boolean clickWithTrustedInput(String selector) {
        return onBrowser(() -> {
            Page p = activePage();
            // Measure only (count); the click itself is the same human BiDi input as every other click
            // (RealInput: wheel scrolling, curved path, occlusion check), never Locator.click or scrollIntoView,
            // whose actionability machinery dispatches page-observable marker events (playwright4j #38).
            Locator target = p.locator(selector).first();
            if (target.count() == 0) return false;
            TabDocument tab = document(p);
            int navigations = tab.navigations;
            MainDocument before = tab.mainDocument;
            try {
                input.click(p, target);
            } catch (RuntimeException unreachable) {
                return false;
            }
            leaveSavedDocument(p);
            awaitNavigationAfterInput(p, tab, navigations, before);
            settleLoad(p);
            return true;
        });
    }

    /**
     * A click that submits a form (a consent wall) navigates a moment later, often to the same URL, so the
     * current document's load state says nothing yet. Waits briefly for a top-level request to start and,
     * if one does, for the final (non-redirect) document response to finish. Protocol events only.
     */
    private static void awaitNavigationAfterInput(Page p, TabDocument tab, int navigations, MainDocument before) {
        try {
            p.waitForCondition(() -> tab.navigations > navigations,
                    new Page.WaitForConditionOptions().setTimeout(1_500));
        } catch (PlaywrightException noNavigation) {
            return;
        }
        try {
            p.waitForCondition(() -> {
                MainDocument main = tab.mainDocument;
                return main != null && main != before && (main.status() < 300 || main.status() >= 400) && main.finished;
            }, new Page.WaitForConditionOptions().setTimeout(15_000));
            p.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(5_000));
        } catch (PlaywrightException slow) {
            // Reading proceeds with the document that is there.
        }
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
            driverProcesses.clear();
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
