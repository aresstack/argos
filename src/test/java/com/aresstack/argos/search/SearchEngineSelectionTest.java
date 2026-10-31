package com.aresstack.argos.search;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The engine selection of one web_search call (issue #73) and the default selection of the JVM property. */
class SearchEngineSelectionTest {
    private static final List<SearchEngine> ALL = SearchEngine.byIds("duckduckgo,bing,google");

    @Test void withoutASelectionAllAvailableEnginesInConfigurationOrder() {
        assertEquals(ALL, SearchEngine.select(null, ALL));
    }

    @Test void aSelectionNamesOnlyItsEnginesInItsOwnOrderEachOnce() {
        assertEquals(List.of(SearchEngine.GOOGLE), SearchEngine.select(List.of("google"), ALL));
        assertEquals(List.of(SearchEngine.BING), SearchEngine.select(List.of("bing"), ALL));
        assertEquals(List.of(SearchEngine.GOOGLE, SearchEngine.BING),
                SearchEngine.select(List.of(" Google ", "BING", "google"), ALL));
    }

    @Test void anEmptySelectionIsRejectedAndNotTakenForAMissingOne() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> SearchEngine.select(List.of(), ALL));
        assertTrue(empty.getMessage().contains("engines is empty"), empty.getMessage());
        assertTrue(empty.getMessage().contains("duckduckgo, bing, google"), empty.getMessage());
    }

    @Test void unknownBlankAndDisabledEnginesAreRejectedNeverCorrected() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> SearchEngine.select(List.of("google", "yahoo"), ALL));
        assertEquals("Unknown search engine: \"yahoo\" (available: duckduckgo, bing, google)", unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SearchEngine.select(List.of(" "), ALL));
        assertThrows(IllegalArgumentException.class, () -> SearchEngine.select(Arrays.asList((String) null), ALL));

        List<SearchEngine> withoutDdg = SearchEngine.byIds("bing,google");
        IllegalArgumentException disabled = assertThrows(IllegalArgumentException.class,
                () -> SearchEngine.select(List.of("duckduckgo"), withoutDdg));
        assertEquals("Search engine not enabled here: \"duckduckgo\" (available: bing, google)", disabled.getMessage());
    }

    @Test void withoutThePropertyTheDefaultIsEveryAvailableEngine() {
        assertEquals(ALL, SearchEngine.defaults(null, ALL));
    }

    @Test void thePropertyChoosesASubsetInItsOwnOrder() {
        assertEquals(List.of(SearchEngine.GOOGLE), SearchEngine.defaults("google", ALL));
        assertEquals(List.of(SearchEngine.GOOGLE, SearchEngine.BING), SearchEngine.defaults(" google , Bing,google", ALL));
        assertEquals(ALL, SearchEngine.defaults("duckduckgo,bing,google", ALL));
    }

    @Test void anEmptyOrInvalidPropertyIsAConfigurationErrorNotAllEngines() {
        for (String invalid : List.of("", " ", " , ", "yahoo", "google,yahoo")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> SearchEngine.defaults(invalid, ALL), invalid);
            assertTrue(error.getMessage().startsWith("Invalid -Dargos.search.defaultEngines="), error.getMessage());
        }
        IllegalArgumentException disabled = assertThrows(IllegalArgumentException.class,
                () -> SearchEngine.defaults("duckduckgo", SearchEngine.byIds("bing,google")));
        assertTrue(disabled.getMessage().contains("not enabled here"), disabled.getMessage());
    }
}
