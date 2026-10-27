package com.aresstack.argos.search;

import com.aresstack.argos.convenience.ConvenienceBrowser;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoad;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoadOutcome;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoadRequest;
import com.aresstack.argos.convenience.NavigationGate;
import com.aresstack.argos.convenience.RegistrableDomain;
import com.aresstack.argos.web.PageCapture;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * navigation of all engines is one parallel load ({@link ConvenienceBrowser#loadTabs}), so no engine waits for
 * another; then each engine's page is evaluated independently by the unchanged {@link WebSearchService} restricted
 * to that one engine (extraction pipeline, consent handling, challenge recognition). An engine's alternative
 * endpoint (DuckDuckGo Lite) is only its own in-job retry, in its own tab. An engine that fails, times out or
 * shows a challenge never holds back the others; a page that loaded but could not be understood comes back as a
 * fallback capture (text and links). The sequential {@link WebSearchService#search} loop over all engines is not
 * used here and stays as it is for the other profiles.
 */
public final class ParallelWebSearch {
    public enum EngineStatus { OK, NO_RESULTS, EXTRACTION_FAILED, CHALLENGE, CONSENT_REQUIRED, TIMEOUT, NAVIGATION_FAILED }

    public record EngineHit(int rank, String title, String url, String snippet) {
    }

    /**
     * @param tab      the engine's tab ("" when none could be opened)
     * @param fallback the result page as loaded, for EXTRACTION_FAILED, CHALLENGE and CONSENT_REQUIRED; else null
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
    /** Budget of the evaluation that follows (extraction, consent click, an engine's own retry endpoint). */
    static final long EVALUATION_MILLIS = 15_000;

    private final ConvenienceBrowser browser;
    private final NavigationGate gate;
    private final List<SearchEngine> engines;
    private final int resultsPerEngine;
    private final boolean acceptAllConsent;
    private final long engineTimeoutMillis;

    public ParallelWebSearch(ConvenienceBrowser browser, NavigationGate gate, List<SearchEngine> engines,
                             int resultsPerEngine, boolean acceptAllConsent, long engineTimeoutMillis) {
        if (engines.isEmpty()) throw new IllegalArgumentException("At least one search engine is required");
        this.browser = browser;
        this.gate = gate;
        this.engines = List.copyOf(engines);
        this.resultsPerEngine = Math.max(1, Math.min(MAX_RESULTS_PER_ENGINE, resultsPerEngine));
        this.acceptAllConsent = acceptAllConsent;
        this.engineTimeoutMillis = Math.max(2_000, engineTimeoutMillis);
    }

    /**
     * Environment: ARGOS_SEARCH_ENGINES (the engines to use, all of them in parallel; default
     * duckduckgo,bing,google), ARGOS_CONVENIENCE_RESULTS_PER_ENGINE (default 10, max 30) and
     * ARGOS_SEARCH_CONSENT_ACCEPT (as for the other profiles).
     */
    public static ParallelWebSearch fromEnvironment(ConvenienceBrowser browser, NavigationGate gate) {
        String configured = System.getenv("ARGOS_SEARCH_ENGINES");
        String perEngine = System.getenv("ARGOS_CONVENIENCE_RESULTS_PER_ENGINE");
        int results = DEFAULT_RESULTS_PER_ENGINE;
        if (perEngine != null && !perEngine.isBlank()) {
            try {
                results = Integer.parseInt(perEngine.trim());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("ARGOS_CONVENIENCE_RESULTS_PER_ENGINE must be an integer: " + perEngine);
            }
        }
        return new ParallelWebSearch(browser, gate, SearchEngine.byIds(configured == null || configured.isBlank()
                ? SearchSettings.DEFAULT_ENGINES : configured), results,
                Boolean.parseBoolean(System.getenv().getOrDefault("ARGOS_SEARCH_CONSENT_ACCEPT", "false").trim()),
                DEFAULT_ENGINE_TIMEOUT_MILLIS);
    }

    public List<SearchEngine> engines() {
        return engines;
    }

    /** One run per configured engine, in configuration order. */
    public List<EngineRun> search(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search query");
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        List<TabLoadRequest> first = new ArrayList<>();
        for (SearchEngine engine : engines) {
            String url = engine.endpoints().get(0).url(encoded);
            first.add(new TabLoadRequest(null, url, RegistrableDomain.of(url)));
        }
        // Fan-out: all engines start together; a slow one does not delay the others' start.
        List<TabLoad> loaded = browser.loadTabs(first, gate, Math.min(4, engines.size()), engineTimeoutMillis);
        // The evaluation gets its own budget: an engine whose page was slow to load (it ends as TIMEOUT) must not
        // use up the time of the engines whose pages are already there.
        long deadline = System.currentTimeMillis() + Math.min(engineTimeoutMillis, EVALUATION_MILLIS);
        ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("argos-search-", 0).factory());
        try {
            List<Future<EngineRun>> runs = new ArrayList<>();
            for (int i = 0; i < engines.size(); i++) {
                SearchEngine engine = engines.get(i);
                TabLoad page = loaded.get(i);
                runs.add(workers.submit(() -> evaluate(engine, query, page, deadline)));
            }
            List<EngineRun> results = new ArrayList<>();
            for (int i = 0; i < engines.size(); i++) {
                String tab = loaded.get(i).tab();
                try {
                    long wait = Math.max(1, deadline - System.currentTimeMillis());
                    results.add(runs.get(i).get(wait, TimeUnit.MILLISECONDS));
                } catch (TimeoutException late) {
                    runs.get(i).cancel(true);
                    results.add(new EngineRun(engines.get(i).id(), EngineStatus.TIMEOUT, tab, List.of(), null,
                            "no answer in time"));
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

    private EngineRun evaluate(SearchEngine engine, String query, TabLoad first, long deadline) {
        if (first.outcome() == TabLoadOutcome.COOLING_DOWN) {
            return new EngineRun(engine.id(), EngineStatus.CHALLENGE, first.tab(), List.of(), null,
                    "access limited recently; try again in about " + first.retryAfterSeconds() + " s");
        }
        TabSearchPages pages = new TabSearchPages(engine, first, deadline);
        WebSearchService service = new WebSearchService(pages,
                new SearchSettings(List.of(engine), resultsPerEngine, Duration.ofMinutes(10), acceptAllConsent));
        WebSearchService.Report report;
        try {
            report = service.search(query);
        } catch (WebSearchService.SearchUnavailableException unreachable) {
            EngineStatus status = pages.timedOut ? EngineStatus.TIMEOUT : EngineStatus.NAVIGATION_FAILED;
            return new EngineRun(engine.id(), status, pages.tab, List.of(), null, pages.lastError);
        }
        List<EngineHit> hits = new ArrayList<>();
        report.hits().forEach(hit -> hits.add(new EngineHit(hit.rank(), hit.title(), hit.url(), hit.snippet())));
        if (!hits.isEmpty()) return new EngineRun(engine.id(), EngineStatus.OK, pages.tab, hits, null, "");
        EngineStatus status = status(report);
        String detail = report.attempts().isEmpty() ? "" : report.attempts().get(report.attempts().size() - 1).outcome().name();
        PageCapture fallback = status == EngineStatus.EXTRACTION_FAILED || status == EngineStatus.CHALLENGE
                || status == EngineStatus.CONSENT_REQUIRED ? pages.firstCapture : null;
        return new EngineRun(engine.id(), status, pages.tab, List.of(), fallback, detail);
    }

    /** The engine's verdict: a challenge or consent wall wins, then an explicit "no results", else not understood. */
    private static EngineStatus status(WebSearchService.Report report) {
        if (!report.challenges().isEmpty()) return EngineStatus.CHALLENGE;
        List<WebSearchService.AttemptOutcome> outcomes = report.attempts().stream().map(WebSearchService.Attempt::outcome).toList();
        if (outcomes.contains(WebSearchService.AttemptOutcome.CHALLENGE_PENDING)) return EngineStatus.CHALLENGE;
        if (outcomes.contains(WebSearchService.AttemptOutcome.CONSENT_REQUIRED)) return EngineStatus.CONSENT_REQUIRED;
        if (outcomes.contains(WebSearchService.AttemptOutcome.NO_ORGANIC_RESULTS)) return EngineStatus.NO_RESULTS;
        if (outcomes.contains(WebSearchService.AttemptOutcome.EXTRACTION_FAILED)) return EngineStatus.EXTRACTION_FAILED;
        return EngineStatus.NAVIGATION_FAILED;
    }

    /**
     * The search's browser port bound to one engine's tab. The first page is the one the fan-out already loaded;
     * a further endpoint (the engine's own retry) loads in the same tab under the navigation gate.
     */
    private final class TabSearchPages implements SearchPageBrowser {
        private final TabLoad first;
        private final long deadline;
        private boolean firstUsed;
        String tab;
        PageCapture firstCapture;
        String lastError = "";
        boolean timedOut;

        TabSearchPages(SearchEngine engine, TabLoad first, long deadline) {
            this.first = first;
            this.deadline = deadline;
            this.tab = first.tab();
        }

        @Override
        public LoadedPage open(String url) throws Exception {
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
            return page(load);
        }

        @Override
        public LoadedPage current() throws Exception {
            return page(browser.readTab(tab));
        }

        @Override
        public boolean click(String cssSelector) {
            return !tab.isEmpty() && browser.clickInTab(cssSelector, tab);
        }

        private LoadedPage page(TabLoad load) throws Exception {
            if (load.outcome() != TabLoadOutcome.OK) {
                if (load.outcome() == TabLoadOutcome.TIMEOUT) timedOut = true;
                lastError = load.outcome() + (load.error().isBlank() ? "" : ": " + firstLine(load.error()));
                throw new IllegalStateException(lastError);
            }
            PageCapture capture = load.capture();
            if (firstCapture == null) firstCapture = capture;
            return new LoadedPage(capture.url(), capture.title(), capture.status(), capture.html());
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 160 ? line.substring(0, 160) : line;
    }
}
