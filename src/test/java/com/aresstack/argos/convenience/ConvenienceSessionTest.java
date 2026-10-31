package com.aresstack.argos.convenience;

import com.aresstack.argos.search.ParallelWebSearch;
import com.aresstack.argos.search.ParallelWebSearch.EngineHit;
import com.aresstack.argos.search.ParallelWebSearch.EngineRun;
import com.aresstack.argos.search.ParallelWebSearch.EngineStatus;
import com.aresstack.argos.search.SearchEngine;
import com.aresstack.argos.web.PageCapture;
import com.aresstack.argos.web.SearchHit;
import com.aresstack.argos.web.UrlPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Section 14 of the convenience specification, against controlled local servers (no real browser). */
class ConvenienceSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Result pages; the engines' start pages are served by startPages().
    private static final String DDG = "https://html.duckduckgo.com/html/?q=";
    private static final String BING = "https://www.bing.com/search";
    private static final String GOOGLE = "https://www.google.com/search";

    private final LocalSites sites = new LocalSites();
    private final DomainCoordinator coordinator = new DomainCoordinator(4, System::currentTimeMillis);

    ConvenienceSessionTest() throws Exception {
    }

    @AfterEach void stop() {
        sites.close();
    }

    // ---- web_search ---------------------------------------------------------------------------------------------

    @Test void allEnginesSearchInParallelEachInItsOwnTab() throws Exception {
        startPages(600, 600, 600);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = searching(browser, 20_000, null);

        long start = System.nanoTime();
        Map<String, Object> answer = session.search("camoufox");
        long millis = (System.nanoTime() - start) / 1_000_000;

        List<LocalSites.Hit> ddg = sites.hits("html.duckduckgo.com"), bing = sites.hits("www.bing.com"),
                google = sites.hits("www.google.com");
        // Each engine: its start page, then the result page its search field asked for.
        assertEquals(List.of("/html/", "/html/"), ddg.stream().map(LocalSites.Hit::path).toList());
        assertEquals(List.of("/", "/search"), bing.stream().map(LocalSites.Hit::path).toList());
        assertEquals(List.of("/", "/search"), google.stream().map(LocalSites.Hit::path).toList());
        List<String> tabs = browser.submitted.stream().map(line -> line.substring(0, line.indexOf(' '))).toList();
        assertEquals(3, tabs.stream().distinct().count(), "each engine typed into in its own tab: " + browser.submitted);
        assertEquals(java.util.Set.of("input[name=q] | camoufox", "#sb_form_q | camoufox", "textarea[name=q] | camoufox"),
                browser.submitted.stream().map(line -> line.substring(line.indexOf("| ") + 2))
                        .collect(java.util.stream.Collectors.toSet()));
        // The three start page requests overlap in time; sequentially they would need 1.8 s.
        assertTrue(ddg.get(0).overlaps(bing.get(0)) && bing.get(0).overlaps(google.get(0))
                && ddg.get(0).overlaps(google.get(0)), sites.hits.toString());
        assertTrue(millis < 1_500, "took " + millis + " ms");

        assertEquals("s1", answer.get("searchId"));
        assertEquals("COMPLETE", answer.get("status"));
        List<Map<String, Object>> engines = list(answer, "engines");
        assertEquals(List.of("duckduckgo", "bing", "google"), engines.stream().map(e -> e.get("id")).toList());
        assertEquals(3, engines.stream().map(e -> e.get("tabId")).distinct().count(), "one tab per engine");
        engines.forEach(engine -> assertEquals("OK", engine.get("status")));
        List<Map<String, Object>> results = list(answer, "results");
        assertEquals("r-s1-1", results.get(0).get("resultId"));
        // Interleaved by rank: rank 1 of every engine first, in configuration order.
        assertEquals(List.of(Map.of("engine", "duckduckgo", "rank", 1)), results.get(0).get("sources"));
        assertEquals(List.of(Map.of("engine", "bing", "rank", 1)), results.get(1).get("sources"));
        assertEquals(List.of(Map.of("engine", "google", "rank", 1)), results.get(2).get("sources"));
        assertNoUrls(answer);
        assertEquals("https://camoufox.com/", session.url("r-s1-1"));
    }

    @Test void aSelectionOfOneEngineOpensOnlyThatEngine() throws Exception {
        startPages(0, 0, 0);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = searching(browser, 10_000, null);

        Map<String, Object> answer = session.search("camoufox", List.of("google"));

        assertEquals(List.of("/", "/search"), sites.hits("www.google.com").stream().map(LocalSites.Hit::path).toList());
        assertTrue(sites.hits("www.bing.com").isEmpty(), "Bing is neither opened nor asked");
        assertTrue(sites.hits("html.duckduckgo.com").isEmpty(), "DuckDuckGo is neither opened nor asked");
        assertEquals(1, browser.submitted.size(), browser.submitted.toString());
        assertEquals(List.of("google"), list(answer, "engines").stream().map(e -> e.get("id")).toList());
        assertEquals("COMPLETE", answer.get("status"));
        List<Map<String, Object>> results = list(answer, "results");
        assertFalse(results.isEmpty());
        results.forEach(result -> assertEquals(List.of("google"), ((List<?>) result.get("sources")).stream()
                .map(source -> ((Map<?, ?>) source).get("engine")).distinct().toList()));
    }

    @Test void aSelectionOfTwoEnginesSkipsTheThirdAndKeepsTheGivenOrder() throws Exception {
        startPages(0, 0, 0);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        ConvenienceSession session = searching(new LocalConvenienceBrowser(sites), 10_000, null);

        Map<String, Object> answer = session.search("camoufox", List.of("google", "bing", "google"));

        assertTrue(sites.hits("html.duckduckgo.com").isEmpty(), "no DuckDuckGo");
        assertEquals(List.of("google", "bing"), list(answer, "engines").stream().map(e -> e.get("id")).toList());
        List<Map<String, Object>> results = list(answer, "results");
        assertEquals(List.of(Map.of("engine", "google", "rank", 1)), results.get(0).get("sources"));
        assertEquals(List.of(Map.of("engine", "bing", "rank", 1)), results.get(1).get("sources"));
        assertEquals("COMPLETE", answer.get("status"));
    }

    @Test void anInvalidSelectionIsRejectedBeforeTheBrowserChanges() throws Exception {
        startPages(0, 0, 0);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = searching(browser, 10_000, null);
        List<String> engineTabs = list(session.search("camoufox"), "engines").stream()
                .map(e -> (String) e.get("tabId")).toList();
        int requests = sites.hits.size();

        for (List<String> invalid : List.of(List.<String>of(), List.of("yahoo"), List.of("google", "altavista"))) {
            assertThrows(IllegalArgumentException.class, () -> session.search("camoufox", invalid), invalid.toString());
        }

        assertEquals(requests, sites.hits.size(), "no engine was asked");
        engineTabs.forEach(tab -> assertTrue(browser.hasOpenTab(tab), "the last search's tabs stay open"));
        assertEquals("s2", session.search("camoufox", List.of("bing")).get("searchId"), "a rejected call is no search");
    }

    @Test void theDefaultSelectionAppliesWithoutEnginesAndTheCallOverridesIt() throws Exception {
        startPages(0, 0, 0);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        List<SearchEngine> available = SearchEngine.byIds("duckduckgo,bing,google");
        ParallelWebSearch search = new ParallelWebSearch(browser, coordinator, available,
                SearchEngine.defaults("google", available), 10, false, 10_000, 45_000);
        ConvenienceSession session = new ConvenienceSession(browser, coordinator, UrlPolicy.off(), search, null);

        Map<String, Object> byDefault = session.search("camoufox");
        assertEquals(List.of("google"), list(byDefault, "engines").stream().map(e -> e.get("id")).toList());
        assertTrue(sites.hits("www.bing.com").isEmpty() && sites.hits("html.duckduckgo.com").isEmpty());

        Map<String, Object> explicit = session.search("camoufox", List.of("bing"));
        assertEquals(List.of("bing"), list(explicit, "engines").stream().map(e -> e.get("id")).toList());
        assertEquals(1, sites.hits("www.google.com").stream().filter(hit -> hit.path().equals("/")).count(),
                "the explicit call does not add the default engine");
        assertEquals(List.of("duckduckgo", "bing", "google"), session.engineIds(), "every available engine may be named");
    }

    @Test void concurrentSearchesWithDifferentSelectionsDoNotAffectEachOther() throws Exception {
        startPages(300, 300, 300);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        ConvenienceSession chatA = searching(new LocalConvenienceBrowser(sites), 10_000, null);
        ConvenienceSession chatB = searching(new LocalConvenienceBrowser(sites), 10_000, null);

        CompletableFuture<Map<String, Object>> a = CompletableFuture.supplyAsync(
                () -> chatA.search("camoufox", List.of("google")));
        CompletableFuture<Map<String, Object>> b = CompletableFuture.supplyAsync(
                () -> chatB.search("camoufox", List.of("bing", "duckduckgo")));

        assertEquals(List.of("google"), list(a.get(), "engines").stream().map(e -> e.get("id")).toList());
        assertEquals(List.of("bing", "duckduckgo"), list(b.get(), "engines").stream().map(e -> e.get("id")).toList());
        assertEquals(List.of("duckduckgo", "bing", "google"),
                list(chatA.search("camoufox"), "engines").stream().map(e -> e.get("id")).toList(),
                "a selection holds for its own call only");
    }

    @Test void aSlowEngineTimesOutWithoutHoldingBackTheOthers() throws Exception {
        startPages(0, 0, 8_000);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        ConvenienceSession session = searching(new LocalConvenienceBrowser(sites), 2_000, null);

        long start = System.nanoTime();
        Map<String, Object> answer = session.search("camoufox");
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 4_000, "took " + millis + " ms");
        assertEquals("PARTIAL", answer.get("status"));
        List<Map<String, Object>> engines = list(answer, "engines");
        assertEquals("OK", engines.get(0).get("status"));
        assertEquals("OK", engines.get(1).get("status"));
        assertEquals("TIMEOUT", engines.get(2).get("status"));
        assertEquals(6, list(answer, "results").size());
    }

    @Test void noResultsExtractionFailureAndConsentAreDistinctWithAFallbackPage() throws Exception {
        startPages(0, 0, 0);
        sites.fixture(DDG, "no-results.html", 0).fixture(BING, "unknown-layout.html", 0)
                .fixture(GOOGLE, "consent-accept-only.html", 0);
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = searching(browser, 10_000, null);

        Map<String, Object> answer = session.search("camoufox");

        List<Map<String, Object>> engines = list(answer, "engines");
        assertEquals("NO_RESULTS", engines.get(0).get("status"));
        assertFalse(engines.get(0).containsKey("fallbackPage"), "an explicit empty result needs no fallback");
        assertEquals("EXTRACTION_FAILED", engines.get(1).get("status"));
        assertEquals("CONSENT_REQUIRED", engines.get(2).get("status"));
        assertTrue(browser.clicked.isEmpty(), "no consent click without SEARCH_CONSENT_ACCEPT");
        @SuppressWarnings("unchecked")
        Map<String, Object> fallback = (Map<String, Object>) engines.get(1).get("fallbackPage");
        assertFalse(String.valueOf(fallback.get("text")).isBlank());
        assertEquals(false, fallback.get("truncated"));
        List<Map<String, Object>> links = list(fallback, "links");
        String tab = (String) engines.get(1).get("tabId");
        assertEquals("l-" + tab + "-1", links.get(0).get("linkId"));
        assertEquals("bing.com", links.get(0).get("domain"));
        assertEquals("https://www.bing.com/retry", session.url("l-" + tab + "-1"));
        assertTrue(engines.get(2).containsKey("fallbackPage"));
        assertEquals("PARTIAL", answer.get("status"));
        assertTrue(list(answer, "results").isEmpty());
        assertNoUrls(answer);
    }

    @Test void mergingKeepsUrlsExactAndCollectsSources() {
        String plain = "https://a.example.org/page";
        List<EngineRun> runs = List.of(
                run("duckduckgo", plain, "https://b.example.org/x?_sg=1&_tp=2"),
                run("bing", plain, "https://b.example.org/x?_sg=9&_tp=2", "https://b.example.org/x?_sg=1&_tp=2#part"),
                run("google", "https://b.example.org/x?_sg=1&_tp=2"));
        ConvenienceSession session = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator,
                UrlPolicy.off(), (query, engines) -> runs, null);

        Map<String, Object> answer = session.search("x");
        List<Map<String, Object>> results = list(answer, "results");

        assertEquals(4, results.size());
        assertEquals(List.of(Map.of("engine", "duckduckgo", "rank", 1), Map.of("engine", "bing", "rank", 1)),
                results.get(0).get("sources"));
        // Created by google's rank 1 (position 1 comes before position 2), then found again as duckduckgo's rank 2.
        assertEquals(List.of(Map.of("engine", "google", "rank", 1), Map.of("engine", "duckduckgo", "rank", 2)),
                results.get(1).get("sources"));
        assertEquals(plain, session.url("r-s1-1"));
        assertEquals("https://b.example.org/x?_sg=1&_tp=2", session.url("r-s1-2"));
        assertEquals("https://b.example.org/x?_sg=9&_tp=2", session.url("r-s1-3"));
        assertEquals("https://b.example.org/x?_sg=1&_tp=2#part", session.url("r-s1-4"));
        assertEquals("example.org", RegistrableDomain.of(plain));
    }

    @Test void oneHistoryEventPerSearchForAllEngines() throws Exception {
        startPages(0, 0, 0);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        RecordingHistory history = new RecordingHistory();
        ConvenienceSession session = searching(new LocalConvenienceBrowser(sites), 10_000, history);

        session.search("camoufox");

        assertEquals(1, history.searches.size());
        assertEquals("duckduckgo OK, bing OK, google OK", history.searches.get(0).get(1));
        assertTrue(history.pages.isEmpty(), "result pages of the engines are no page reads");
        assertEquals(6, sites.hits.size(), "start page and result page per engine, no extra requests for the history");
    }

    // ---- opening pages ------------------------------------------------------------------------------------------

    @Test void selectedResultsOpenInParallelTabsInSelectionOrderWithTextAndLinks() throws Exception {
        sites.page("https://one.org/a", 200, page("One", "first page", "https://one.org/next"), 400, Map.of())
                .page("https://two.net/b", 200, page("Two", "second page", "https://two.net/next"), 400, Map.of())
                .page("https://three.io/c", 200, page("Three", "third page", "https://three.io/next"), 400, Map.of());
        RecordingHistory history = new RecordingHistory();
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = new ConvenienceSession(browser, coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(run("bing", "https://one.org/a", "https://two.net/b", "https://three.io/c")), history);
        session.search("x");

        long start = System.nanoTime();
        Map<String, Object> answer = session.selectResults("s1", List.of("r-s1-3", "r-s1-1", "r-s1-2", "r-s1-1", "r-s1-9"));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 1_000, "took " + millis + " ms");
        List<Map<String, Object>> opened = list(answer, "opened");
        assertEquals(List.of("r-s1-3", "r-s1-1", "r-s1-2"), opened.stream().map(page -> page.get("resultId")).toList());
        assertEquals(List.of("Three", "One", "Two"), opened.stream().map(page -> page.get("title")).toList());
        Map<String, Object> first = opened.get(0);
        assertEquals("three.io", first.get("domain"));
        assertEquals("OK", first.get("status"));
        assertEquals("text/html", first.get("contentType"));
        assertTrue(String.valueOf(first.get("text")).contains("third page"));
        assertEquals(false, first.get("truncated"));
        String tab = (String) first.get("tabId");
        assertEquals(List.of(Map.of("linkId", "l-" + tab + "-1", "text", "next", "domain", "three.io")), first.get("links"));
        assertEquals("listed twice", list(answer, "skipped").get(0).get("reason"));
        assertEquals("r-s1-9", list(answer, "errors").get(0).get("resultId"));
        assertEquals(3, history.pages.size());
        assertNoUrls(answer);
        // Overlap of the three page loads.
        List<LocalSites.Hit> hits = new ArrayList<>(sites.hits);
        assertTrue(hits.get(0).overlaps(hits.get(1)) && hits.get(1).overlaps(hits.get(2)), hits.toString());
    }

    @Test void oneNavigationPerRegistrableDomainAcrossConcurrentCallsAndChats() throws Exception {
        List<String> sameDomain = List.of("https://docs.example.co.uk/1", "https://www.example.co.uk/2",
                "https://example.co.uk/3", "https://shop.example.co.uk/4", "https://news.example.co.uk/5");
        for (String url : sameDomain) sites.page(url, 200, page("p", "text", null), 300, Map.of());
        sites.page("https://other.org/x", 200, page("other", "text", null), 300, Map.of());
        assertEquals("example.co.uk", RegistrableDomain.of("https://docs.example.co.uk/1"), "Public Suffix List");

        ConvenienceSession chatA = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(run("bing", sameDomain.get(0), sameDomain.get(1), sameDomain.get(2), "https://other.org/x")), null);
        ConvenienceSession chatB = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(run("bing", sameDomain.get(3), sameDomain.get(4))), null);
        chatA.search("a");
        chatB.search("b");

        CompletableFuture<Map<String, Object>> a = CompletableFuture.supplyAsync(
                () -> chatA.selectResults("s1", List.of("r-s1-1", "r-s1-2", "r-s1-3", "r-s1-4")));
        CompletableFuture<Map<String, Object>> b = CompletableFuture.supplyAsync(
                () -> chatB.selectResults("s1", List.of("r-s1-1", "r-s1-2")));

        assertEquals(4, list(a.get(), "opened").size());
        assertEquals(2, list(b.get(), "opened").size());
        List<LocalSites.Hit> example = sites.hitsOfDomain("example.co.uk");
        assertEquals(5, example.size());
        for (int i = 0; i < example.size(); i++) {
            for (int j = i + 1; j < example.size(); j++) {
                assertFalse(example.get(i).overlaps(example.get(j)), "same domain overlapped: " + example);
            }
        }
        LocalSites.Hit other = sites.hits("other.org").get(0);
        assertTrue(example.stream().anyMatch(other::overlaps), "other domains are not held back");
    }

    @Test void atMostFourNavigationsRunAtOnceAcrossChats() throws Exception {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            urls.add("https://site" + i + ".org/");
            sites.page(urls.get(i), 200, page("p" + i, "text", null), 300, Map.of());
        }
        ConvenienceSession chatA = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(run("bing", urls.subList(0, 4).toArray(String[]::new))), null);
        ConvenienceSession chatB = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(run("bing", urls.subList(4, 8).toArray(String[]::new))), null);
        chatA.search("a");
        chatB.search("b");

        long start = System.nanoTime();
        CompletableFuture<Map<String, Object>> a = CompletableFuture.supplyAsync(
                () -> chatA.selectResults("s1", List.of("r-s1-1", "r-s1-2", "r-s1-3", "r-s1-4")));
        CompletableFuture<Map<String, Object>> b = CompletableFuture.supplyAsync(
                () -> chatB.selectResults("s1", List.of("r-s1-1", "r-s1-2", "r-s1-3", "r-s1-4")));
        assertEquals(4, list(a.get(), "opened").size());
        assertEquals(4, list(b.get(), "opened").size());
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(sites.maxInFlight.get() <= 4, "in flight: " + sites.maxInFlight.get());
        assertTrue(millis >= 550, "eight loads of 300 ms in at least two waves, took " + millis + " ms");
    }

    @Test void tooManyRequestsIsReportedAndItsRetryAfterRespected() throws Exception {
        sites.page("https://limited.org/a", 429, page("Slow down", "too many", null), 0, Map.of("Retry-After", "120"))
                .page("https://www.limited.org/b", 200, page("b", "text", null), 0, Map.of());
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = new ConvenienceSession(browser, coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(run("bing", "https://limited.org/a", "https://www.limited.org/b")), null);
        session.search("x");

        Map<String, Object> first = session.selectResults("s1", List.of("r-s1-1"));
        Map<String, Object> error = list(first, "errors").get(0);
        assertTrue(String.valueOf(error.get("error")).contains("429"), error.toString());
        assertEquals(120L, error.get("retryAfterSeconds"));
        assertTrue(list(first, "opened").isEmpty());
        assertEquals(0, browser.openTabs(), "the failed tab is closed");
        assertTrue(coordinator.cooldownMillis("limited.org") > 110_000);

        Map<String, Object> second = session.selectResults("s1", List.of("r-s1-2"));
        assertTrue(String.valueOf(list(second, "errors").get(0).get("error")).contains("refused recent requests"));
        assertEquals(1, sites.hitsOfDomain("limited.org").size(), "no request while the site asked to wait");
    }

    @Test void longPagesAreMarkedTruncatedAndReadInParts() throws Exception {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 200; i++) links.append("<a href=\"https://long.org/p").append(i).append("\">p").append(i).append("</a> ");
        String body = "<html><head><title>Long</title></head><body><p>" + "word ".repeat(6_000) + "</p>" + links + "</body></html>";
        sites.page("https://long.org/", 200, body, 0, Map.of());
        ConvenienceSession session = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator,
                UrlPolicy.off(), (query, engines) -> List.of(run("bing", "https://long.org/")), null);
        session.search("x");

        Map<String, Object> page = list(session.selectResults("s1", List.of("r-s1-1")), "opened").get(0);

        assertEquals(true, page.get("truncated"));
        assertEquals(1, page.get("part"));
        assertTrue((int) page.get("parts") >= 3);
        assertEquals(ConvenienceSession.MAX_LINKS, list(page, "links").size());
        assertEquals(true, page.get("linksTruncated"));
        Map<String, Object> second = session.readTab((String) page.get("tabId"), 2);
        assertEquals(2, second.get("part"));
        assertFalse(String.valueOf(second.get("text")).isBlank());
        assertThrows(IllegalArgumentException.class, () -> session.readTab((String) page.get("tabId"), 99));
    }

    @Test void followLinkGivesNewIdsAndRejectsTheEarlierPagesIds() throws Exception {
        sites.page("https://site.org/a", page("A", "page a", "https://site.org/b"))
                .page("https://site.org/b", page("B", "page b", "https://site.org/c"));
        ConvenienceSession session = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator,
                UrlPolicy.off(), (query, engines) -> List.of(run("bing", "https://site.org/a")), null);
        session.search("x");
        String tab = (String) list(session.selectResults("s1", List.of("r-s1-1")), "opened").get(0).get("tabId");

        Map<String, Object> b = session.followLink(tab, "l-" + tab + "-1");

        assertEquals("B", b.get("title"));
        assertEquals("l-" + tab + "-2", list(b, "links").get(0).get("linkId"));
        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> session.followLink(tab, "l-" + tab + "-1"));
        assertTrue(stale.getMessage().contains("earlier page"), stale.getMessage());
        assertThrows(IllegalArgumentException.class, () -> session.followLink("t99", "l-" + tab + "-2"));

        Map<String, Object> back = session.back(tab);
        assertEquals("A", back.get("title"));
        assertEquals("l-" + tab + "-3", list(back, "links").get(0).get("linkId"));
    }

    @Test void closingTabsKeepsTheOthersAndInvalidatesTheirIds() throws Exception {
        sites.page("https://one.org/", page("One", "1", "https://one.org/x"))
                .page("https://two.org/", page("Two", "2", "https://two.org/x"));
        ConvenienceSession session = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator,
                UrlPolicy.off(), (query, engines) -> List.of(run("bing", "https://one.org/", "https://two.org/")), null);
        session.search("x");
        List<Map<String, Object>> opened = list(session.selectResults("s1", List.of("r-s1-1", "r-s1-2")), "opened");
        String one = (String) opened.get(0).get("tabId"), two = (String) opened.get(1).get("tabId");

        Map<String, Object> closed = session.closeTabs(List.of(one));

        assertEquals(List.of(one), closed.get("closed"));
        assertTrue(((List<?>) closed.get("openTabs")).contains(two));
        assertEquals("Two", session.readTab(two, 1).get("title"));
        assertThrows(IllegalArgumentException.class, () -> session.readTab(one, 1));
        assertThrows(IllegalArgumentException.class, () -> session.url("l-" + one + "-1"));
        assertEquals("https://two.org/x", session.url("l-" + two + "-1"));
        // Opening the same result again is possible after closing; an open one is reported instead.
        Map<String, Object> again = session.selectResults("s1", List.of("r-s1-1", "r-s1-2"));
        assertEquals(1, list(again, "opened").size());
        assertEquals("already open", list(again, "skipped").get(0).get("reason"));
        assertEquals(two, list(again, "skipped").get(0).get("tabId"));
    }

    @Test void idsOfAnotherChatOrContextAreRejected() throws Exception {
        sites.page("https://one.org/", page("One", "1", "https://one.org/x"));
        LocalConvenienceBrowser browserA = new LocalConvenienceBrowser(sites);
        ConvenienceSession chatA = new ConvenienceSession(browserA, coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(run("bing", "https://one.org/")), null);
        ConvenienceSession chatB = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator,
                UrlPolicy.off(), (query, engines) -> List.of(), null);
        chatA.search("x");
        String tab = (String) list(chatA.selectResults("s1", List.of("r-s1-1")), "opened").get(0).get("tabId");

        assertThrows(IllegalArgumentException.class, () -> chatB.readTab(tab, 1));
        assertThrows(IllegalArgumentException.class, () -> chatB.selectResults("s1", List.of("r-s1-1")));
        assertThrows(IllegalArgumentException.class, () -> chatB.url("r-s1-1"));
        browserA.context = "ctx-2";
        assertThrows(IllegalArgumentException.class, () -> chatA.readTab(tab, 1));
    }

    @Test void theTabLimitIsReportedClearly() throws Exception {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            urls.add("https://s" + i + ".org/");
            sites.page(urls.get(i), page("p" + i, "t", null));
        }
        ConvenienceSession session = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator,
                UrlPolicy.off(), (query, engines) -> List.of(run("bing", urls.toArray(String[]::new))), null);
        session.search("x");
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 21; i++) ids.add("r-s1-" + i);

        session.selectResults("s1", ids.subList(0, 10));
        session.selectResults("s1", ids.subList(10, 20));
        Map<String, Object> over = session.selectResults("s1", ids.subList(20, 21));

        assertTrue(list(over, "opened").isEmpty());
        assertTrue(String.valueOf(list(over, "skipped").get(0).get("reason")).contains("tab limit"));
        assertThrows(IllegalArgumentException.class, () -> session.selectResults("s1", ids.subList(0, 11)));
    }

    @Test void aUrlWhoseTabWentWithTheClosedBrowserOpensAgain() throws Exception {
        sites.page("https://a.org/", page("A", "t", null));
        sites.page("https://b.org/", page("B", "t", null));
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = new ConvenienceSession(browser, coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(), null);
        String before = (String) list(session.openUrl("https://a.org/"), "opened").get(0).get("tabId");
        assertEquals("already open", list(session.openUrl("https://a.org/"), "skipped").get(0).get("reason"));

        browser.restartAfterClose(); // Camoufox closed by the user, started again by the next call (#65)

        Map<String, Object> again = session.openUrl("https://a.org/");
        assertTrue(list(again, "skipped").isEmpty(), again.toString());
        String after = (String) list(again, "opened").get(0).get("tabId");
        assertNotEquals(before, after);
        assertEquals("A", session.readTab(after, 1).get("title"));
        assertThrows(IllegalArgumentException.class, () -> session.readTab(before, 1));
        assertEquals(1, list(session.openUrl("https://b.org/"), "opened").size());
        // Duplicates are still recognised among the tabs that really are open.
        assertEquals("already open", list(session.openUrl("https://a.org/"), "skipped").get(0).get("reason"));
    }

    @Test void aTabTheUserClosedNeitherBlocksItsUrlNorCountsTowardsTheLimit() throws Exception {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            urls.add("https://s" + i + ".org/");
            sites.page(urls.get(i), page("p" + i, "t", null));
        }
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = new ConvenienceSession(browser, coordinator, UrlPolicy.off(),
                (query, engines) -> List.of(), null);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) ids.add((String) list(session.openUrl(urls.get(i)), "opened").get(0).get("tabId"));
        assertTrue(String.valueOf(list(session.openUrl(urls.get(20)), "skipped").get(0).get("reason"))
                .contains("tab limit"));

        browser.closeTabQuietly(ids.get(0)); // closed in the browser window, not through web_close_tabs

        assertEquals(1, list(session.openUrl(urls.get(0)), "opened").size());
        browser.restartAfterClose();
        assertEquals(1, list(session.openUrl(urls.get(20)), "opened").size());
    }

    @Test void blockedTargetsAreNeverLoaded() throws Exception {
        ConvenienceSession session = new ConvenienceSession(new LocalConvenienceBrowser(sites), coordinator,
                UrlPolicy.publicOnly(), (query, engines) -> List.of(run("bing", "http://127.0.0.1:9/secret")), null);
        session.search("x");

        Map<String, Object> answer = session.selectResults("s1", List.of("r-s1-1"));

        assertTrue(String.valueOf(list(answer, "errors").get(0).get("error")).startsWith("blocked"));
        assertTrue(sites.hits.isEmpty());
    }

    @Test void aStartPageWithoutSearchFieldIsAnInputFailureWithoutAnyDirectUrl() throws Exception {
        startPages(0, 0, 0);
        sites.page("https://www.bing.com/", "<html><head><title>Bing</title></head><body><p>Wartung</p></body></html>");
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 0);
        ConvenienceSession session = searching(new LocalConvenienceBrowser(sites), 10_000, null);

        Map<String, Object> answer = session.search("camoufox");

        List<Map<String, Object>> engines = list(answer, "engines");
        assertEquals("OK", engines.get(0).get("status"));
        assertEquals("INPUT_FAILED", engines.get(1).get("status"));
        assertTrue(engines.get(1).containsKey("fallbackPage"), "the start page as it was shown");
        assertEquals("OK", engines.get(2).get("status"));
        assertEquals(List.of("/"), sites.hits("www.bing.com").stream().map(LocalSites.Hit::path).toList(),
                "no result URL is opened in place of the search field");
        assertEquals("PARTIAL", answer.get("status"));
    }

    @Test void anEngineThatAlreadyTypedGetsAShortGraceForItsResultPage() throws Exception {
        startPages(0, 0, 0);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 2_500);
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = new ConvenienceSession(browser, coordinator, UrlPolicy.off(),
                new ParallelWebSearch(browser, coordinator, SearchEngine.byIds("duckduckgo,bing,google"), 10, false,
                        10_000, 1_000), null);

        Map<String, Object> answer = session.search("camoufox");

        List<Map<String, Object>> engines = list(answer, "engines");
        assertEquals("OK", engines.get(2).get("status"), engines.toString());
        assertEquals(3, engines.get(2).get("resultCount"));
        assertEquals("COMPLETE", answer.get("status"));
    }

    @Test void aTimeoutNamesTheStepsTheEngineGotThrough() throws Exception {
        startPages(0, 0, 0);
        sites.fixture(DDG, "duckduckgo-html.html", 0).fixture(BING, "bing.html", 0).fixture(GOOGLE, "google.html", 9_000);
        LocalConvenienceBrowser browser = new LocalConvenienceBrowser(sites);
        ConvenienceSession session = new ConvenienceSession(browser, coordinator, UrlPolicy.off(),
                new ParallelWebSearch(browser, coordinator, SearchEngine.byIds("duckduckgo,bing,google"), 10, false,
                        10_000, 1_000), null);

        Map<String, Object> answer = session.search("camoufox");

        Map<String, Object> google = list(answer, "engines").get(2);
        assertEquals("TIMEOUT", google.get("status"));
        assertEquals("no answer in time after: start page www.google.com OK, typing", google.get("detail"));
        assertNoUrls(answer);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The engines' regular start pages, with their real search fields. */
    private void startPages(long ddgDelay, long bingDelay, long googleDelay) {
        sites.fixture("https://html.duckduckgo.com/html/", "duckduckgo-html-home.html", ddgDelay)
                .fixture("https://www.bing.com/", "bing-home.html", bingDelay)
                .fixture("https://www.google.com/", "google-home.html", googleDelay);
    }

    private ConvenienceSession searching(LocalConvenienceBrowser browser, long engineTimeout, RecordingHistory history) {
        ParallelWebSearch search = new ParallelWebSearch(browser, coordinator,
                SearchEngine.byIds("duckduckgo,bing,google"), 10, false, engineTimeout);
        return new ConvenienceSession(browser, coordinator, UrlPolicy.off(), search, history);
    }

    private static EngineRun run(String engine, String... urls) {
        List<EngineHit> hits = new ArrayList<>();
        for (int i = 0; i < urls.length; i++) hits.add(new EngineHit(i + 1, "Title " + (i + 1), urls[i], "snippet"));
        return new EngineRun(engine, hits.isEmpty() ? EngineStatus.NO_RESULTS : EngineStatus.OK, "", hits, null, "");
    }

    private static String page(String title, String text, String link) {
        return "<html><head><title>" + title + "</title></head><body><p>" + text + "</p>"
                + (link == null ? "" : "<a href=\"" + link + "\">next</a>") + "</body></html>";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private static void assertNoUrls(Map<String, Object> answer) throws Exception {
        String json = JSON.writeValueAsString(answer);
        assertFalse(json.contains("://"), json);
    }

    static final class RecordingHistory implements ConvenienceSession.History {
        final List<List<Object>> searches = new ArrayList<>();
        final List<String> pages = new ArrayList<>();

        @Override
        public synchronized void search(String query, String providers, List<SearchHit> hits) {
            searches.add(List.of(query, providers, hits));
        }

        @Override
        public synchronized void page(PageCapture capture, String requestedUrl, boolean newVisit, String tool, String tab) {
            pages.add(tool + " " + tab);
        }
    }
}
