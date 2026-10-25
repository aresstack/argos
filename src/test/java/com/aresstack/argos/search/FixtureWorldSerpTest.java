package com.aresstack.argos.search;

import com.aresstack.argos.eval.FixtureWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The evaluation's search engine must be understood by Argos' search analysis, or every search task fails. */
class FixtureWorldSerpTest {
    private final SearchResultExtractor extractor =
            new SearchResultExtractor(SearchAnalysisSettings.defaults(), SearchLayoutResolver.NONE);

    @ParameterizedTest
    @ValueSource(strings = {"Kestrel Valley Observatory", "Kestrel Valley Observatory official website",
            "Kestrel Valley Observatory clear nights 2025", "Kestrel Valley Observatory opening hours Saturday",
            "Kestrel Valley Observatory dark-sky zone", "dark sky zone kestrel", "kestrel telescope aperture"})
    void searchPagesAreReadAsOrganicResults(String query) throws Exception {
        try (FixtureWorld world = new FixtureWorld()) {
            String template = world.searchTemplate();
            String url = template.replace("{query}", query.replace(' ', '+'));
            SerpDocument document = new SerpDocumentParser(SearchAnalysisSettings.defaults())
                    .parse(world.searchHtml(query), url, query, "", 1);

            var result = extractor.extract(document, query, SearchEngine.custom(template).endpoints().get(0), Set.of());

            assertEquals(SearchOutcome.ORGANIC_RESULTS, result.outcome(), query);
            assertEquals(world.expectedHits(query), result.candidates().size(), query);
            assertTrue(result.candidates().stream().allMatch(c -> c.url().startsWith(world.siteBase())), query);
        }
    }

    @Test
    void aQueryWithoutMatchesIsAnEmptyEngineNotAFailure() throws Exception {
        try (FixtureWorld world = new FixtureWorld()) {
            String template = world.searchTemplate();
            SerpDocument document = new SerpDocumentParser(SearchAnalysisSettings.defaults())
                    .parse(world.searchHtml("quantum zebra"), template.replace("{query}", "quantum+zebra"), "", "", 1);

            var result = extractor.extract(document, "quantum zebra",
                    SearchEngine.custom(template).endpoints().get(0), Set.of());

            assertNotEquals(SearchOutcome.ORGANIC_RESULTS, result.outcome());
            assertTrue(result.candidates().isEmpty());
        }
    }
}
