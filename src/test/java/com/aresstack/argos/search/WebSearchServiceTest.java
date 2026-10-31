package com.aresstack.argos.search;

import com.aresstack.argos.web.SearchResults;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    // The engines' regular start pages: the only URLs the search ever opens.
    private static final String DDG = "https://html.duckduckgo.com/html/";
    private static final String DDG_LITE = "https://lite.duckduckgo.com/lite/";
    private static final String BING = "https://www.bing.com/";
    private static final String GOOGLE = "https://www.google.com/";

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

    // ---- regular browser UI (#67) ----------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"duckduckgo, https://html.duckduckgo.com/html/, input[name=q], duckduckgo-html.html",
            "bing, https://www.bing.com/, #sb_form_q, bing.html",
            "google, https://www.google.com/, textarea[name=q], google.html"})
    void everyEngineIsSearchedThroughItsStartPageAndSearchField(String engine, String home, String field,
                                                                String resultFixture) throws Exception {
        browser.serve(home, resultFixture);

        var report = service(engine).search("Camoufox browser");

        assertEquals(List.of(home), browser.opened, "only the start page is opened");
        assertEquals(List.of(home + " | " + field + " | Camoufox browser"), browser.submitted);
        assertEquals(WebSearchService.AttemptOutcome.ORGANIC_RESULTS, report.attempts().get(0).outcome());
        assertFalse(report.hits().isEmpty());
    }

    @Test
    void noOpenedUrlEverCarriesTheQuery() throws Exception {
        browser.serve(DDG, "unknown-layout.html");
        browser.serve(DDG_LITE, "duckduckgo-lite.html");
        browser.serve(BING, "bing.html");

        service("duckduckgo,bing").search("camoufox");

        assertEquals(List.of(DDG, DDG_LITE), browser.opened);
        browser.opened.forEach(url -> assertFalse(url.contains("camoufox") || url.contains("q="), url));
        assertEquals(2, browser.submitted.size());
    }

    @Test
    void anAlternativeLocatorFindsAChangedSearchField() throws Exception {
        browser.home(BING, "bing-home-input.html");
        browser.serve(BING, "bing.html");

        var report = service("bing").search("camoufox");

        assertEquals(List.of(BING + " | input[name=q] | camoufox"), browser.submitted);
        assertEquals(3, report.hits().size());
    }

    @Test
    void aStartPageWithoutSearchFieldFailsWithoutAnyDirectUrlFallback() {
        browser.home(BING, "no-search-box.html");
        browser.serve(BING, "bing.html");

        var failure = assertThrows(BrowserWebSearch.SearchFailedException.class,
                () -> new BrowserWebSearch(service("bing")).search("camoufox"));

        assertEquals(List.of(BING), browser.opened);
        assertTrue(browser.submitted.isEmpty());
        assertTrue(failure.getMessage().contains("ATTEMPT: www.bing.com SEARCH_BOX_UNAVAILABLE (no search field matches"),
                failure.getMessage());
    }

    @Test
    void aSearchFieldThatRefusesInputIsReportedNotHidden() throws Exception {
        browser.inputFailure = new IllegalStateException("The click did not put the focus into the field");
        browser.serve(GOOGLE, "google.html");

        var report = service("google").search("camoufox");

        assertTrue(report.hits().isEmpty());
        assertEquals(SearchOutcome.EXTRACTION_FAILED, report.outcome(), "never a false OK");
        assertEquals(WebSearchService.AttemptOutcome.SEARCH_BOX_UNAVAILABLE, report.attempts().get(0).outcome());
        assertTrue(report.attempts().get(0).detail().contains("did not put the focus"), report.attempts().get(0).detail());
    }

    @Test
    void aConsentBannerOnTheStartPageIsResolvedBeforeTyping() throws Exception {
        browser.home(GOOGLE, "consent-google.html");
        browser.afterClick = "google-home.html";
        browser.serve(GOOGLE, "google.html");

        var report = service("google").search("camoufox");

        assertEquals(1, browser.clicked.size());
        assertEquals(List.of(GOOGLE + " | textarea[name=q] | camoufox"), browser.submitted);
        assertEquals("https://www.researchgate.net/", report.hits().get(0).url());
    }

    @Test
    void anAcceptOnlyWallOnTheStartPageIsAControlledStop() throws Exception {
        browser.home(GOOGLE, "consent-accept-only.html");
        browser.serve(GOOGLE, "google.html");

        var report = service("google").search("camoufox");

        assertTrue(browser.clicked.isEmpty());
        assertTrue(browser.submitted.isEmpty());
        assertEquals(WebSearchService.AttemptOutcome.CONSENT_REQUIRED, report.attempts().get(0).outcome());
    }

    @Test
    void aChallengeOnTheStartPageLocksTheFamily() throws Exception {
        browser.home(BING, "captcha-bing.html");
        browser.serve(BING, "bing.html");

        var report = service("bing").search("camoufox");

        assertTrue(browser.submitted.isEmpty());
        assertEquals(WebSearchService.AttemptOutcome.CHALLENGE_PENDING, report.attempts().get(0).outcome());
        assertEquals("bing.com", report.challenges().get(0).family());
    }

    @Test
    void theQueryIsTypedAsOneLine() throws Exception {
        browser.serve(BING, "bing.html");

        service("bing").search("  Camoufox\n\tbrowser  ");

        assertEquals(List.of(BING + " | #sb_form_q | Camoufox browser"), browser.submitted);
    }

    private WebSearchService service(String engines) {
        return new WebSearchService(browser, new SearchSettings(SearchEngine.byIds(engines), 10, Duration.ofMinutes(10)),
                SearchLayoutResolver.NONE, clock);
    }

    /**
     * Serves each engine's start page (a realistic home fixture unless replaced) and, once its search field received
     * the query, the engine's result fixture. Anything unknown is unreachable. A submission is only possible through
     * a selector that matches an editable field of the page currently shown, as for the real browser.
     */
    private static final class FakeBrowser implements SearchPageBrowser {
        private static final Map<String, String> HOMES = Map.of(DDG, "duckduckgo-html-home.html",
                DDG_LITE, "duckduckgo-lite-home.html", BING, "bing-home.html", GOOGLE, "google-home.html");
        final Map<String, String> homes = new HashMap<>(HOMES);
        final Map<String, String> results = new HashMap<>();
        final List<String> opened = new ArrayList<>();
        final List<String> clicked = new ArrayList<>();
        /** "start page | selector | typed text" per submission. */
        final List<String> submitted = new ArrayList<>();
        String afterClick;
        RuntimeException inputFailure;
        private String home;
        private String currentUrl;
        private String currentFixture;

        /** The result page the engine behind {@code homeUrl} answers with. */
        void serve(String homeUrl, String resultFixture) {
            results.put(homeUrl, resultFixture);
        }

        void home(String homeUrl, String homeFixture) {
            homes.put(homeUrl, homeFixture);
        }

        @Override
        public LoadedPage open(String url) throws Exception {
            opened.add(url);
            if (!results.containsKey(url) || !homes.containsKey(url)) {
                throw new IllegalStateException("NS_ERROR_UNKNOWN_HOST\ncall log: " + url);
            }
            home = url;
            currentUrl = url;
            currentFixture = homes.get(url);
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

        @Override
        public boolean submitQuery(List<String> searchBoxSelectors, String query) {
            Document page = Jsoup.parse(SerpFixtures.html(currentFixture), currentUrl);
            for (String selector : searchBoxSelectors) {
                Element field = page.selectFirst(selector);
                if (field == null) continue;
                if (inputFailure != null) throw inputFailure;
                assertTrue(field.is("textarea, input:not([type=hidden])"), selector + " is no editable field");
                submitted.add(home + " | " + selector + " | " + query);
                currentFixture = results.get(home);
                return true;
            }
            return false;
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
