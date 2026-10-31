package com.aresstack.argos.search;

import com.aresstack.argos.EnvironmentVariables;
import com.aresstack.argos.convenience.ConvenienceBrowser;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoad;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoadOutcome;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoadRequest;
import com.aresstack.argos.convenience.ConvenienceSession;
import com.aresstack.argos.convenience.NavigationGate;
import com.aresstack.argos.convenience.RegistrableDomain;
import com.aresstack.argos.web.PageCapture;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The convenience profile's web search: every configured engine at once, each in a new tab of its own. The first
 * navigation of all engines, to their regular start pages, is one parallel load ({@link ConvenienceBrowser#loadTabs}),
 * so no engine waits for another; then each engine is searched independently by the unchanged
 * {@link WebSearchService} restricted to that one engine: the same {@link SearchFormInteraction} as for the other
 * profiles (search field, real typing, real Enter in the engine's tab), then the extraction pipeline, consent
 * handling and challenge recognition. An engine's alternative endpoint (DuckDuckGo Lite) is only its own in-job
 * retry, in its own tab. An engine that fails, times out or
 * shows a challenge never holds back the others; a page that loaded but could not be understood comes back as a
 * fallback capture (text and links). A call may name a subset of the configured engines
 * ({@link #search(String, List)}); then only those are opened, the others are not contacted at all. The sequential {@link WebSearchService#search} loop over all engines is not
 * used here and stays as it is for the other profiles.
 */
public final class ParallelWebSearch implements ConvenienceSession.Search {
    public enum EngineStatus {
        OK, NO_RESULTS, EXTRACTION_FAILED, CHALLENGE, CONSENT_REQUIRED, TIMEOUT, NAVIGATION_FAILED,
        /** The start page loaded, but its search field could not be found or operated with real input. */
        INPUT_FAILED
    }

    public record EngineHit(int rank, String title, String url, String snippet) {
    }

    /**
     * @param tab      the engine's tab ("" when none could be opened)
     * @param fallback the page as loaded, for EXTRACTION_FAILED, CHALLENGE, CONSENT_REQUIRED and INPUT_FAILED (the
     *                 start page); else null
     * @param detail   short diagnostic without URLs
     */
    public record EngineRun(String engine, EngineStatus status, String tab, List<EngineHit> hits, PageCapture fallback,
                            String detail) {
        public EngineRun {
            hits = List.copyOf(hits);
        }
    }

    public static final int DEFAULT_RESULTS_PER_ENGINE = 10;
    public static final int MAX_RESULTS_PER_ENGINE = 30;
    /** Budget of the parallel first navigation of all engines. */
    public static final long DEFAULT_ENGINE_TIMEOUT_MILLIS = 20_000;
    /**
     * Budget of the search that follows: typing into each engine's field, the result page, extraction, consent click,
     * an engine's own retry endpoint. The engines type at the same time: each key is a short turn of the one
     * browser thread, the pauses between keys are spent off it (issue #79).
     */
    static final long EVALUATION_MILLIS = 45_000;

    private final ConvenienceBrowser browser;
    private final NavigationGate gate;
    private final List<SearchEngine> engines;
    /** The engines of a call that names none: all configured ones, or the default selection. */
    private final List<SearchEngine> defaults;
    private final int resultsPerEngine;
    private final boolean acceptAllConsent;
    private final long engineTimeoutMillis;
    private final long evaluationMillis;

    public ParallelWebSearch(ConvenienceBrowser browser, NavigationGate gate, List<SearchEngine> engines,
                             int resultsPerEngine, boolean acceptAllConsent, long engineTimeoutMillis) {
        this(browser, gate, engines, resultsPerEngine, acceptAllConsent, engineTimeoutMillis, EVALUATION_MILLIS);
    }

    /** With an explicit budget for the search after the start pages (tests); see {@link #EVALUATION_MILLIS}. */
    public ParallelWebSearch(ConvenienceBrowser browser, NavigationGate gate, List<SearchEngine> engines,
                             int resultsPerEngine, boolean acceptAllConsent, long engineTimeoutMillis,
                             long evaluationMillis) {
        this(browser, gate, engines, engines, resultsPerEngine, acceptAllConsent, engineTimeoutMillis, evaluationMillis);
    }

    /**
     * @param engines  the available engines (SEARCH_ENGINES): the only ones a call may name
     * @param defaults the engines of a call that names none, a subset of {@code engines}
     *                 ({@link SearchEngine#defaults})
     */
    public ParallelWebSearch(ConvenienceBrowser browser, NavigationGate gate, List<SearchEngine> engines,
                             List<SearchEngine> defaults, int resultsPerEngine, boolean acceptAllConsent,
                             long engineTimeoutMillis, long evaluationMillis) {
        if (engines.isEmpty()) throw new IllegalArgumentException("At least one search engine is required");
        if (defaults.isEmpty() || !engines.containsAll(defaults)) {
            throw new IllegalArgumentException("The default engines must be some of the available engines");
        }
        this.browser = browser;
        this.gate = gate;
        this.engines = List.copyOf(engines);
        this.defaults = List.copyOf(defaults);
        this.resultsPerEngine = Math.max(1, Math.min(MAX_RESULTS_PER_ENGINE, resultsPerEngine));
        this.acceptAllConsent = acceptAllConsent;
        this.engineTimeoutMillis = Math.max(2_000, engineTimeoutMillis);
        this.evaluationMillis = Math.max(1_000, evaluationMillis);
    }

    /**
     * Environment: SEARCH_ENGINES (the engines available, searched in parallel; default
     * duckduckgo,bing,google), the JVM property {@value SearchEngine#DEFAULT_ENGINES_PROPERTY} (the engines of a
     * call that names none; default all available ones), CONVENIENCE_RESULTS_PER_ENGINE (default 10, max 30) and
     * SEARCH_CONSENT_ACCEPT (as for the other profiles).
     */
    public static ParallelWebSearch fromEnvironment(ConvenienceBrowser browser, NavigationGate gate) {
        String configured = EnvironmentVariables.system().get("SEARCH_ENGINES");
        String perEngine = EnvironmentVariables.system().get("CONVENIENCE_RESULTS_PER_ENGINE");
        int results = DEFAULT_RESULTS_PER_ENGINE;
        if (perEngine != null && !perEngine.isBlank()) {
            try {
                results = Integer.parseInt(perEngine.trim());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("CONVENIENCE_RESULTS_PER_ENGINE must be an integer: " + perEngine);
            }
        }
        List<SearchEngine> available = SearchEngine.byIds(configured == null || configured.isBlank()
                ? SearchSettings.DEFAULT_ENGINES : configured);
        return new ParallelWebSearch(browser, gate, available,
                SearchEngine.defaults(System.getProperty(SearchEngine.DEFAULT_ENGINES_PROPERTY), available), results,
                Boolean.parseBoolean(EnvironmentVariables.system().getOrDefault("SEARCH_CONSENT_ACCEPT", "false").trim()),
                DEFAULT_ENGINE_TIMEOUT_MILLIS, EVALUATION_MILLIS);
    }

    public List<SearchEngine> engines() {
        return engines;
    }

    /** The engines of a call that names none. */
    public List<SearchEngine> defaults() {
        return defaults;
    }

    /** One run per default engine (all configured ones unless a default selection is set), in their order. */
    public List<EngineRun> search(String query) {
        return search(query, null);
    }

    /**
     * One run per selected engine, in the order of the selection; without a selection (null) the default engines.
     * The selection holds for this call only and may name any available engine, also one outside the defaults; see
     * {@link SearchEngine#select}.
     */
    public List<EngineRun> search(String query, List<String> engineIds) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search query");
        List<SearchEngine> engines = engineIds == null ? defaults : SearchEngine.select(engineIds, this.engines);
        List<TabLoadRequest> first = new ArrayList<>();
        for (SearchEngine engine : engines) {
            String url = engine.endpoints().get(0).homeUrl();
            first.add(new TabLoadRequest(null, url, RegistrableDomain.of(url)));
        }
        // Fan-out: all engines start together; a slow one does not delay the others' start.
        List<TabLoad> loaded = browser.loadTabs(first, gate, Math.min(4, engines.size()), engineTimeoutMillis);
        // The evaluation gets its own budget: an engine whose page was slow to load (it ends as TIMEOUT) must not
        // use up the time of the engines whose pages are already there.
        long deadline = System.currentTimeMillis() + evaluationMillis;
        ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("argos-search-", 0).factory());
        try {
            List<Future<EngineRun>> runs = new ArrayList<>();
            List<TabSearchPages> tabs = new ArrayList<>();
            for (int i = 0; i < engines.size(); i++) {
                SearchEngine engine = engines.get(i);
                TabSearchPages pages = new TabSearchPages(engine, loaded.get(i), deadline);
                tabs.add(pages);
                runs.add(workers.submit(() -> evaluate(engine, query, pages)));
            }
            List<EngineRun> results = new ArrayList<>();
            for (int i = 0; i < engines.size(); i++) {
                String tab = loaded.get(i).tab();
                try {
                    long wait = Math.max(1, deadline - System.currentTimeMillis());
                    results.add(runs.get(i).get(wait, TimeUnit.MILLISECONDS));
                } catch (TimeoutException late) {
                    results.add(grace(engines.get(i), query, runs.get(i), tabs.get(i)));
                } catch (Exception failed) {
                    results.add(new EngineRun(engines.get(i).id(), EngineStatus.NAVIGATION_FAILED, tab, List.of(),
                            null, WebSearchService.safeMessage(failed)));
                }
            }
            return results;
        } finally {
            // Never wait here for an engine that ran out of time; its browser steps are bounded by their own timeouts.
            workers.shutdownNow();
        }
    }

    @Override
    public List<EngineRun> run(String query, List<String> engineIds) {
        return search(query, engineIds);
    }

    @Override
    public List<String> engineIds() {
        return engines.stream().map(SearchEngine::id).toList();
    }

    @Override
    public void checkEngines(List<String> engineIds) {
        SearchEngine.select(engineIds, engines);
    }

    private EngineRun evaluate(SearchEngine engine, String query, TabSearchPages pages) {
        TabLoad first = pages.first;
        if (first.outcome() == TabLoadOutcome.COOLING_DOWN) {
            return new EngineRun(engine.id(), EngineStatus.CHALLENGE, first.tab(), List.of(), null,
                    "access limited recently; try again in about " + first.retryAfterSeconds() + " s");
        }
        WebSearchService service = new WebSearchService(pages, settingsFor(engine),
                attempt -> pages.steps.add(attempt.host() + " " + attempt.outcome()));
        WebSearchService.Report report;
        try {
            report = service.search(query);
        } catch (WebSearchService.SearchUnavailableException unreachable) {
            if (pages.timedOut) {
                return new EngineRun(engine.id(), EngineStatus.TIMEOUT, pages.tab, List.of(), null,
                        pages.lastError + " after: " + String.join(", ", pages.steps));
            }
            return new EngineRun(engine.id(), EngineStatus.NAVIGATION_FAILED, pages.tab, List.of(), null, pages.lastError);
        }
        List<EngineHit> hits = hits(report);
        if (!hits.isEmpty()) return new EngineRun(engine.id(), EngineStatus.OK, pages.tab, hits, null, "");
        EngineStatus status = status(report);
        String detail = String.join("; ", report.attempts().stream()
                .map(attempt -> attempt.host() + " " + attempt.outcome()).toList());
        PageCapture fallback = status == EngineStatus.EXTRACTION_FAILED || status == EngineStatus.CHALLENGE
                || status == EngineStatus.CONSENT_REQUIRED || status == EngineStatus.INPUT_FAILED
                ? pages.lastCapture : null;
        return new EngineRun(engine.id(), status, pages.tab, List.of(), fallback, detail);
    }

    private SearchSettings settingsFor(SearchEngine engine) {
        return new SearchSettings(List.of(engine), resultsPerEngine, Duration.ofMinutes(10), acceptAllConsent);
    }

    private static List<EngineHit> hits(WebSearchService.Report report) {
        List<EngineHit> hits = new ArrayList<>();
        report.hits().forEach(hit -> hits.add(new EngineHit(hit.rank(), hit.title(), hit.url(), hit.snippet())));
        return hits;
    }

    /** How much longer an engine that already typed its query may take after the deadline. */
    static final long GRACE_MILLIS = 5_000;
    /**
     * How long the one read of a late engine's tab may take. The browser may still be busy with a step the stopped
     * job started (they run under their own, longer timeouts); the search does not wait for that.
     */
    static final long HARVEST_MILLIS = 3_000;

    /**
     * An engine that ran out of time while its typed query was being answered (the result page still loading or
     * being read) gets a short grace to finish: the search was done, only the page came late. If it is still not
     * done then, it is stopped (no further browser step of it starts) and its tab is read once as it is: the result
     * page may well be there while the engine's job still waits for the page to settle, or was slowed down by the
     * other engines' work on the one browser. Results found on that page count; nothing is typed or loaded again.
     * An engine that had not typed yet, or whose tab shows no results, stays TIMEOUT. The detail names the steps the
     * engine got through, without URLs.
     */
    private EngineRun grace(SearchEngine engine, String query, Future<EngineRun> run, TabSearchPages pages) {
        if (pages.typed) {
            try {
                return run.get(GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (Exception stillLate) {
                // the tab is read below
            }
        }
        pages.abandoned = true;
        // What the engine got through until now; a stopped job's own failures afterwards are no step of the search.
        String steps = String.join(", ", pages.steps);
        run.cancel(true);
        if (pages.typed) {
            List<EngineHit> late = harvestInTime(engine, query, pages);
            if (!late.isEmpty()) {
                return new EngineRun(engine.id(), EngineStatus.OK, pages.tab, late, null,
                        "results read from the loaded page after the time limit");
            }
        }
        return new EngineRun(engine.id(), EngineStatus.TIMEOUT, pages.tab, List.of(), null,
                "no answer in time after: " + steps);
    }

    /** {@link #harvest}, given up after {@link #HARVEST_MILLIS}; the read itself is left to finish on its own. */
    private List<EngineHit> harvestInTime(SearchEngine engine, String query, TabSearchPages pages) {
        java.util.concurrent.FutureTask<List<EngineHit>> read =
                new java.util.concurrent.FutureTask<>(() -> harvest(engine, query, pages));
        Thread.ofVirtual().name("argos-search-harvest").start(read);
        try {
            return read.get(HARVEST_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception tooLate) {
            read.cancel(true);
            return List.of();
        }
    }

    /**
     * The results the engine's tab shows now, for the endpoint whose search field was used last; read only (one
     * serialized DOM), never a click or navigation. Empty when the tab is gone, shows another page or no results.
     */
    private List<EngineHit> harvest(SearchEngine engine, String query, TabSearchPages pages) {
        SearchEngine.Endpoint endpoint = pages.endpoint;
        if (endpoint == null || pages.tab.isEmpty()) return List.of();
        try {
            TabLoad load = browser.readTab(pages.tab);
            if (load.outcome() != TabLoadOutcome.OK || load.capture() == null) return List.of();
            PageCapture capture = load.capture();
            List<EngineHit> hits = new ArrayList<>();
            new WebSearchService(pages, settingsFor(engine))
                    .resultsOn(new SearchPageBrowser.LoadedPage(capture.url(), capture.title(), capture.status(),
                            capture.html()), query, engine, endpoint)
                    .forEach(hit -> hits.add(new EngineHit(hit.rank(), hit.title(), hit.url(), hit.snippet())));
            return hits;
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /** The engine's verdict: a challenge or consent wall wins, then an explicit "no results", else not understood. */
    private static EngineStatus status(WebSearchService.Report report) {
        if (!report.challenges().isEmpty()) return EngineStatus.CHALLENGE;
        List<WebSearchService.AttemptOutcome> outcomes = report.attempts().stream().map(WebSearchService.Attempt::outcome).toList();
        if (outcomes.contains(WebSearchService.AttemptOutcome.CHALLENGE_PENDING)) return EngineStatus.CHALLENGE;
        if (outcomes.contains(WebSearchService.AttemptOutcome.CONSENT_REQUIRED)) return EngineStatus.CONSENT_REQUIRED;
        if (outcomes.contains(WebSearchService.AttemptOutcome.NO_ORGANIC_RESULTS)) return EngineStatus.NO_RESULTS;
        if (outcomes.contains(WebSearchService.AttemptOutcome.EXTRACTION_FAILED)) return EngineStatus.EXTRACTION_FAILED;
        if (outcomes.contains(WebSearchService.AttemptOutcome.SEARCH_BOX_UNAVAILABLE)) return EngineStatus.INPUT_FAILED;
        return EngineStatus.NAVIGATION_FAILED;
    }

    /**
     * The search's browser port bound to one engine's tab. The first start page is the one the fan-out already
     * loaded; a further endpoint (the engine's own retry) loads in the same tab, and the navigation that pressing
     * Enter starts runs under the navigation gate too.
     */
    private final class TabSearchPages implements SearchPageBrowser {
        final SearchEngine engine;
        final TabLoad first;
        private final long deadline;
        private boolean firstUsed;
        volatile String tab;
        /** Whether typing a query into the tab has begun, so a result page may be on its way. */
        volatile boolean typed;
        /** What the engine got through, without URLs: "start page html.duckduckgo.com", "typed", ... */
        final List<String> steps = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** The page the tab showed last, start page or result page. */
        volatile PageCapture lastCapture;
        /** The endpoint whose start page the tab opened last: the one a result page in the tab answers. */
        volatile SearchEngine.Endpoint endpoint;
        /** Set when the engine ran out of time: no further browser step of its job starts. */
        volatile boolean abandoned;
        String lastError = "";
        boolean timedOut;

        TabSearchPages(SearchEngine engine, TabLoad first, long deadline) {
            this.engine = engine;
            this.first = first;
            this.deadline = deadline;
            this.tab = first.tab();
        }

        @Override
        public LoadedPage open(String url) throws Exception {
            checkActive();
            TabLoad load;
            if (!firstUsed && url.equals(first.requestedUrl())) {
                firstUsed = true;
                load = first;
            } else {
                long left = deadline - System.currentTimeMillis();
                if (left < 1_000) {
                    timedOut = true;
                    throw new IllegalStateException("no time left for the engine's other endpoint");
                }
                load = browser.loadTabs(List.of(new TabLoadRequest(tab.isEmpty() ? null : tab, url,
                        RegistrableDomain.of(url))), gate, 1, left).get(0);
                if (!load.tab().isEmpty()) tab = load.tab();
            }
            steps.add("start page " + DomainNames.hostOf(url) + " " + load.outcome());
            endpoint = engine.endpoints().stream().filter(candidate -> candidate.homeUrl().equals(url)).findFirst()
                    .orElse(null);
            return page(load);
        }

        @Override
        public LoadedPage current() throws Exception {
            checkActive();
            return page(browser.readTab(tab));
        }

        @Override
        public boolean click(String cssSelector) {
            return !abandoned && !tab.isEmpty() && browser.clickInTab(cssSelector, tab);
        }

        @Override
        public boolean submitQuery(List<String> searchBoxSelectors, String query) throws Exception {
            checkActive();
            if (tab.isEmpty()) return false;
            String domain = RegistrableDomain.of(lastCapture == null ? first.requestedUrl() : lastCapture.url());
            NavigationGate.Lease lease;
            while ((lease = gate.tryAcquire(domain)) == null) {
                if (System.currentTimeMillis() > deadline - 1_000) {
                    timedOut = true;
                    throw new IllegalStateException("no time left to search in the engine's tab");
                }
                Thread.sleep(20);
            }
            TabLoad load = null;
            steps.add("typing");
            typed = true;
            try {
                load = browser.submitSearchInTab(tab, searchBoxSelectors, query);
                steps.add(load == null ? "no search field" : "result page read");
                return load != null;
            } catch (RuntimeException refused) {
                steps.add("search field refused input");
                throw refused;
            } finally {
                lease.release(load == null || load.capture() == null ? 0 : load.capture().status(),
                        load == null ? 0 : load.retryAfterSeconds(), load != null && load.challenge());
            }
        }

        private void checkActive() {
            if (abandoned) throw new IllegalStateException("the engine ran out of time");
        }

        private LoadedPage page(TabLoad load) throws Exception {
            if (load.outcome() != TabLoadOutcome.OK) {
                if (load.outcome() == TabLoadOutcome.TIMEOUT) timedOut = true;
                lastError = load.outcome() + (load.error().isBlank() ? "" : ": " + firstLine(load.error()));
                throw new IllegalStateException(lastError);
            }
            PageCapture capture = load.capture();
            lastCapture = capture;
            return new LoadedPage(capture.url(), capture.title(), capture.status(), capture.html());
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 160 ? line.substring(0, 160) : line;
    }
}
