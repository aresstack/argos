package com.aresstack.argos.search;

import com.aresstack.argos.web.SearchResults;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchServiceTest {
    private static final String DDG = "https://html.duckduckgo.com/html/?q=camoufox";
    private static final String DDG_LITE = "https://lite.duckduckgo.com/lite/?q=camoufox";
    private static final String BING = "https://www.bing.com/search?q=camoufox";
    private static final String GOOGLE = "https://www.google.com/search?q=camoufox";

    private final FakeBrowser browser = new FakeBrowser();
    private final MutableClock clock = new MutableClock();

    @Test
    void firstEngineThatDeliversEndsTheSearch() throws Exception {
        browser.serve(DDG, "duckduckgo-html.html");
        browser.serve(BING, "bing.html");

        var report = service("duckduckgo,bing").search("camoufox");

        assertEquals(3, report.hits().size());
        assertEquals("https://camoufox.com/", report.hits().get(0).url());
        assertEquals(List.of(DDG), browser.opened);
        assertEquals(WebSearchService.AttemptOutcome.ORGANIC_RESULTS, report.attempts().get(0).outcome());
        assertEquals(List.of("html.duckduckgo.com"), report.providerHosts());
    }

    @Test
    void ununderstoodEndpointFallsBackToTheProvidersNextEndpoint() throws Exception {
        browser.serve(DDG, "unknown-layout.html");
        browser.serve(DDG_LITE, "duckduckgo-lite.html");

        var report = service("duckduckgo").search("camoufox");

        assertEquals(List.of(DDG, DDG_LITE), browser.opened);
        assertEquals(WebSearchService.AttemptOutcome.EXTRACTION_FAILED, report.attempts().get(0).outcome());
        assertEquals(2, report.hits().size());
    }

    @Test
    void explicitlyEmptyEngineSkipsItsOtherEndpointsButNotTheNextEngine() throws Exception {
        browser.serve(DDG, "no-results.html");
        browser.serve(BING, "bing.html");

        var report = service("duckduckgo,bing").search("camoufox");

        assertEquals(List.of(DDG, BING), browser.opened);
        assertEquals(WebSearchService.AttemptOutcome.NO_ORGANIC_RESULTS, report.attempts().get(0).outcome());
        assertEquals(3, report.hits().size());
    }

    @Test
    void challengedFamilyIsLockedInsteadOfRetried() throws Exception {
        browser.serve(BING, "captcha-bing.html");
        browser.serve(DDG, "duckduckgo-html.html");
        WebSearchService service = service("bing,duckduckgo");

        var first = service.search("camoufox");
        assertEquals(1, first.challenges().size());
        assertEquals("bing.com", first.challenges().get(0).family());
        assertEquals(3, first.hits().size());

        browser.opened.clear();
        var second = service.search("camoufox");
        assertEquals(List.of(DDG), browser.opened);
        assertEquals(WebSearchService.AttemptOutcome.CHALLENGE_PENDING, second.attempts().get(0).outcome());

        clock.advance(Duration.ofMinutes(11));
        browser.opened.clear();
        service.search("camoufox");
        assertEquals(BING, browser.opened.get(0));
    }

    @Test
    void consentIsResolvedWithOneTrustedClickBeforeReading() throws Exception {
        browser.serve(GOOGLE, "consent-google.html");
        browser.afterClick = "google.html";

        var report = service("google").search("camoufox");

        assertEquals(1, browser.clicked.size());
        assertEquals(3, report.hits().size());
        assertEquals("https://www.researchgate.net/", report.hits().get(0).url());
    }

    @Test
    void resultsPagesAreNeverClicked() throws Exception {
        browser.serve(BING, "bing.html");

        service("bing").search("camoufox");

        assertTrue(browser.clicked.isEmpty());
    }

    @Test
    void unreachableEnginesAreATechnicalFailureNotAnEmptySearch() {
        assertThrows(WebSearchService.SearchUnavailableException.class, () -> service("duckduckgo,bing").search("x"));
    }

    @Test
    void consentWallWithOnlyAcceptAllIsAControlledStopWithoutOptIn() throws Exception {
        browser.serve(GOOGLE, "consent-accept-only.html");
        browser.afterClick = "google.html";
        browser.serve(BING, "bing.html");

        var report = service("google,bing").search("camoufox");

        assertTrue(browser.clicked.isEmpty());
        assertEquals(WebSearchService.AttemptOutcome.CONSENT_REQUIRED, report.attempts().get(0).outcome());
        assertEquals("https://w3.org/TR/webdriver-bidi/", report.hits().get(0).url());
    }

    @Test
    void acceptAllIsClickedOnlyWithExplicitOptIn() throws Exception {
        browser.serve(GOOGLE, "consent-accept-only.html");
        browser.afterClick = "google.html";
        WebSearchService optedIn = new WebSearchService(browser, new SearchSettings(SearchEngine.byIds("google"), 10,
                Duration.ofMinutes(10), true), SearchLayoutResolver.NONE, clock);

        var report = optedIn.search("camoufox");

        assertEquals(1, browser.clicked.size());
        assertEquals("https://www.researchgate.net/", report.hits().get(0).url());
    }

    @Test
    void unreadableResultPagesAreAFailureNotNoResults() {
        browser.serve(DDG, "unknown-layout.html");
        browser.serve(DDG_LITE, "unknown-layout.html");

        var failure = assertThrows(BrowserWebSearch.SearchFailedException.class,
                () -> new BrowserWebSearch(service("duckduckgo")).search("camoufox"));

        assertTrue(failure.getMessage().contains("ATTEMPT: html.duckduckgo.com EXTRACTION_FAILED"), failure.getMessage());
        assertTrue(failure.getMessage().contains("ATTEMPT: lite.duckduckgo.com EXTRACTION_FAILED"));
    }

    @Test
    void portReportsHitsAndDiagnostics() throws Exception {
        browser.serve(DDG, "no-results.html");
        browser.serve(BING, "bing.html");

        SearchResults results = new BrowserWebSearch(service("duckduckgo,bing")).search("camoufox");

        assertEquals("WebDriver BiDi - W3C", results.hits().get(0).title());
        assertEquals(3, results.hits().size());
        assertEquals(List.of("PROVIDER: html.duckduckgo.com", "PROVIDER: www.bing.com",
                "ATTEMPT: html.duckduckgo.com NO_ORGANIC_RESULTS", "ATTEMPT: www.bing.com ORGANIC_RESULTS"),
                results.notes());
    }

    @Test
    void explicitlyEmptySearchIsNoResultsNotAFailure() throws Exception {
        browser.serve(DDG, "no-results.html");

        SearchResults results = new BrowserWebSearch(service("duckduckgo")).search("camoufox");

        assertTrue(results.hits().isEmpty());
    }

    private WebSearchService service(String engines) {
        return new WebSearchService(browser, new SearchSettings(SearchEngine.byIds(engines), 10, Duration.ofMinutes(10)),
                SearchLayoutResolver.NONE, clock);
    }

    /** Serves fixtures by URL; anything unknown is unreachable. */
    private static final class FakeBrowser implements SearchPageBrowser {
        final Map<String, String> fixtures = new HashMap<>();
        final List<String> opened = new ArrayList<>();
        final List<String> clicked = new ArrayList<>();
        String afterClick;
        private String currentUrl;
        private String currentFixture;

        void serve(String url, String fixture) {
            fixtures.put(url, fixture);
        }

        @Override
        public LoadedPage open(String url) throws Exception {
            opened.add(url);
            String fixture = fixtures.get(url);
            if (fixture == null) throw new IllegalStateException("NS_ERROR_UNKNOWN_HOST\ncall log: " + url);
            currentUrl = url;
            currentFixture = fixture;
            return current();
        }

        @Override
        public LoadedPage current() {
            return new LoadedPage(currentUrl, "", 200, SerpFixtures.html(currentFixture));
        }

        @Override
        public boolean click(String cssSelector) {
            clicked.add(cssSelector);
            if (afterClick != null) currentFixture = afterClick;
            return true;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-25T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
