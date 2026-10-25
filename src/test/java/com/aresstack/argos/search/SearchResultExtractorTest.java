package com.aresstack.argos.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SearchResultExtractorTest {
    private static final String DDG_HTML = "https://html.duckduckgo.com/html/?q=camoufox";
    private static final String DDG_LITE = "https://lite.duckduckgo.com/lite/?q=camoufox";
    private static final String BING = "https://www.bing.com/search?q=webdriver+bidi";
    private static final String GOOGLE = "https://www.google.com/search?q=researchgate+api";
    private static final String LOCAL = "http://127.0.0.1:8080/search?q=solar";

    private final SearchResultExtractor extractor =
            new SearchResultExtractor(SearchAnalysisSettings.defaults(), SearchLayoutResolver.NONE);

    @Test
    void duckDuckGoAdapterSkipsAdsAndUnwrapsRedirects() {
        var endpoint = SearchEngine.DUCKDUCKGO.endpoints().get(0);
        var result = extract("duckduckgo-html.html", DDG_HTML, endpoint, SearchEngine.DUCKDUCKGO);

        assertEquals(SearchOutcome.ORGANIC_RESULTS, result.outcome());
        assertEquals(List.of("https://camoufox.com/", "https://github.com/daijro/camoufox",
                "https://pypi.org/project/camoufox/"), urls(result));
        assertEquals("Camoufox - Stealthy Firefox for automation", result.candidates().get(0).title());
        assertTrue(result.candidates().get(0).snippet().contains("open source anti-detect browser"));
        assertFalse(result.candidates().get(0).snippet().contains("Stealthy Firefox for automation"));
    }

    @Test
    void duckDuckGoLiteListsBareLinksThroughTheHarvest() {
        var endpoint = SearchEngine.DUCKDUCKGO.endpoints().get(1);
        var result = extract("duckduckgo-lite.html", DDG_LITE, endpoint, SearchEngine.DUCKDUCKGO);

        assertEquals(SearchOutcome.ORGANIC_RESULTS, result.outcome());
        assertEquals(List.of("https://camoufox.com/", "https://github.com/daijro/camoufox"), urls(result));
    }

    @Test
    void bingAdapterDecodesCkLinksAndSkipsAds() {
        var endpoint = SearchEngine.BING.endpoints().get(0);
        var result = extract("bing.html", BING, endpoint, SearchEngine.BING);

        assertEquals(SearchOutcome.ORGANIC_RESULTS, result.outcome());
        assertEquals(List.of("https://w3.org/TR/webdriver-bidi/",
                "https://developer.mozilla.org/en-US/docs/Web/WebDriver",
                "https://playwright.dev/docs/api/class-browser"), urls(result));
        assertEquals("WebDriver BiDi - W3C", result.candidates().get(0).title());
        assertEquals("This document specifies a bidirectional protocol for remote control of user agents.",
                result.candidates().get(0).snippet());
    }

    @Test
    void bingIsStillUnderstoodWhenTheAdapterSelectorNoLongerMatches() {
        var changedLayout = new SearchEngine.Endpoint("https://www.bing.com/search?q={query}", "li.renamed_algo", false);
        var result = extract("bing.html", BING, changedLayout, SearchEngine.BING);

        assertEquals(SearchOutcome.ORGANIC_RESULTS, result.outcome());
        assertEquals("https://w3.org/TR/webdriver-bidi/", result.candidates().get(0).url());
        assertEquals(3, result.candidates().size());
        assertFalse(urls(result).stream().anyMatch(url -> url.contains("bing.com")));
    }

    @Test
    void duckDuckGoAndGoogleAreAlsoUnderstoodWithoutAdapterSelectors() {
        var noSelector = new SearchEngine.Endpoint("https://example.invalid/?q={query}", "", false);
        var ddg = extract("duckduckgo-html.html", DDG_HTML, noSelector, SearchEngine.DUCKDUCKGO);
        var google = extract("google.html", GOOGLE, noSelector, SearchEngine.GOOGLE);

        assertEquals("https://camoufox.com/", ddg.candidates().get(0).url());
        assertFalse(urls(ddg).stream().anyMatch(url -> url.contains("duckduckgo.com")));
        assertEquals("https://www.researchgate.net/", google.candidates().get(0).url());
        assertFalse(urls(google).stream().anyMatch(url -> url.contains("google")));
    }

    @Test
    void googleTitlesComeFromTheHeadingInsideTheLink() {
        var endpoint = SearchEngine.GOOGLE.endpoints().get(0);
        var result = extract("google.html", GOOGLE, endpoint, SearchEngine.GOOGLE);

        assertEquals(SearchOutcome.ORGANIC_RESULTS, result.outcome());
        assertEquals(List.of("https://www.researchgate.net/", "https://stackoverflow.com/questions/123/researchgate-api",
                "https://github.com/topics/researchgate"), urls(result));
        assertEquals("ResearchGate | Find and share research", result.candidates().get(0).title());
        assertTrue(result.candidates().get(0).snippet().startsWith("Access 160+ million publications"));
    }

    @Test
    void unknownEngineLayoutIsUnderstoodMechanically() {
        var endpoint = SearchEngine.custom("http://127.0.0.1:8080/search?q={query}").endpoints().get(0);
        var result = extract("generic-cards.html", LOCAL, endpoint, SearchEngine.custom(LOCAL));

        assertEquals(SearchOutcome.ORGANIC_RESULTS, result.outcome());
        assertEquals(4, result.candidates().size());
        assertEquals("Perovskite solar cells reach new efficiency", result.candidates().get(0).title());
        assertFalse(urls(result).stream().anyMatch(url -> url.contains("/topics/")));
    }

    @Test
    void explicitNoResultsIsNotAnExtractionFailure() {
        var endpoint = SearchEngine.DUCKDUCKGO.endpoints().get(0);
        var result = extract("no-results.html", DDG_HTML, endpoint, SearchEngine.DUCKDUCKGO);

        assertEquals(SearchOutcome.NO_ORGANIC_RESULTS, result.outcome());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void ununderstoodLayoutIsAnExtractionFailureNotAnEmptyEngine() {
        var endpoint = SearchEngine.custom(LOCAL).endpoints().get(0);
        var result = extract("unknown-layout.html", LOCAL, endpoint, SearchEngine.custom(LOCAL));

        assertEquals(SearchOutcome.EXTRACTION_FAILED, result.outcome());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void lowConfidenceLayoutCanBeRepairedByAValidatedAiDecision() {
        SerpDocument document = SerpFixtures.parse("two-cards.html", LOCAL, "");
        assertEquals(SearchOutcome.EXTRACTION_FAILED, extractor.extract(document, "batteries",
                SearchEngine.custom(LOCAL).endpoints().get(0), Set.of()).outcome());

        SerpContainer hits = document.containers().stream()
                .filter(c -> c.classTokens().contains("hits")).findFirst().orElseThrow();
        List<String> blocks = hits.childIds();
        SearchLayoutResolver resolver = request -> Optional.of(new SearchLayoutResolver.Decision(
                request.snapshotId(), hits.id(), blocks, 0.8));
        var repaired = new SearchResultExtractor(SearchAnalysisSettings.defaults(), resolver)
                .extract(document, "batteries", SearchEngine.custom(LOCAL).endpoints().get(0), Set.of());

        assertEquals(SearchOutcome.ORGANIC_RESULTS, repaired.outcome());
        assertEquals(List.of("https://alpha.example/one", "https://beta.example/two"), urls(repaired));
    }

    @Test
    void aiDecisionWithInventedIdsIsRejected() {
        SerpDocument document = SerpFixtures.parse("two-cards.html", LOCAL, "");
        SearchLayoutResolver inventing = request -> Optional.of(new SearchLayoutResolver.Decision(
                request.snapshotId(), "container-9999", List.of(), 0.9));
        var result = new SearchResultExtractor(SearchAnalysisSettings.defaults(), inventing)
                .extract(document, "batteries", SearchEngine.custom(LOCAL).endpoints().get(0), Set.of());

        assertEquals(SearchOutcome.EXTRACTION_FAILED, result.outcome());
        assertTrue(result.diagnostics().stream().anyMatch(line -> line.contains("rejected")));
    }

    private SearchResultExtractor.Extraction extract(String fixture, String url, SearchEngine.Endpoint endpoint,
                                                     SearchEngine engine) {
        SerpDocument document = SerpFixtures.parse(fixture, url, endpoint.resultBlockSelector());
        return extractor.extract(document, "query", endpoint, engine.chromeDomains());
    }

    private static List<String> urls(SearchResultExtractor.Extraction result) {
        return result.candidates().stream().map(SearchResultExtractor.Candidate::url).toList();
    }
}
