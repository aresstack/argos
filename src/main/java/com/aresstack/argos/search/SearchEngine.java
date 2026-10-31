package com.aresstack.argos.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One search engine as a user knows it ("DuckDuckGo"), with its technical endpoints as alternative
 * ways to ask the same provider. Every endpoint is used the way a person uses it: its regular start page is
 * opened, the query is typed into its search field and Enter is pressed; Argos never builds a result URL itself.
 * An endpoint may name the result blocks it is known to render; when that selector stops matching (layout
 * change), the mechanical analysis takes over.
 *
 * @param chromeDomains domains of the engine's own page furniture (legal, consent, settings) that are
 *                      never harvested as results
 */
public record SearchEngine(String id, String displayName, List<Endpoint> endpoints, Set<String> chromeDomains) {

    /**
     * @param homeUrl                 the engine's regular start page, opened without any query
     * @param searchBoxSelectors      CSS selectors of the start page's search field, tried in order (alternatives
     *                                for layout changes); the first one that takes real input is used
     * @param resultBlockSelector     CSS selector of organic result blocks, or blank
     * @param harvestWhenUnstructured whether external links count as results when no block structure exists
     */
    public record Endpoint(String homeUrl, List<String> searchBoxSelectors, String resultBlockSelector,
                           boolean harvestWhenUnstructured) {
        public Endpoint {
            searchBoxSelectors = List.copyOf(searchBoxSelectors);
            if (searchBoxSelectors.isEmpty()) throw new IllegalArgumentException("Endpoint " + homeUrl + " has no search box");
        }

        String host() {
            return DomainNames.hostOf(homeUrl);
        }
    }

    /** The usual names of a search field, for engines without a known layout. */
    static final List<String> GENERIC_SEARCH_BOX = List.of("textarea[name=q]", "input[name=q]", "input[type=search]");

    public SearchEngine {
        endpoints = List.copyOf(endpoints);
        chromeDomains = Set.copyOf(chromeDomains);
        if (endpoints.isEmpty()) throw new IllegalArgumentException("Search engine " + id + " has no endpoint");
    }

    public static final SearchEngine DUCKDUCKGO = new SearchEngine("duckduckgo", "DuckDuckGo", List.of(
            // Server-rendered start pages: the HTML one first, Lite as its own fallback.
            new Endpoint("https://html.duckduckgo.com/html/", List.of("input[name=q]"),
                    "div.result:not(.result--ad)", false),
            new Endpoint("https://lite.duckduckgo.com/lite/", List.of("input[name=q]"), "", true)),
            Set.of("duckduckgo.com"));

    public static final SearchEngine BING = new SearchEngine("bing", "Bing", List.of(
            new Endpoint("https://www.bing.com/", List.of("#sb_form_q", "textarea[name=q]", "input[name=q]"),
                    "#b_results > li.b_algo", false)),
            Set.of("bing.com", "microsoft.com", "microsofttranslator.com", "go.microsoft.com"));

    public static final SearchEngine GOOGLE = new SearchEngine("google", "Google", List.of(
            new Endpoint("https://www.google.com/", List.of("textarea[name=q]", "input[name=q]"),
                    "#rso div.g, #rso div.MjjYud", false)),
            Set.of("google.com", "google.de", "googleusercontent.com", "gstatic.com", "googleadservices.com",
                    "doubleclick.net"));

    public static List<SearchEngine> catalog() {
        return List.of(DUCKDUCKGO, BING, GOOGLE);
    }

    /** Engines in the given order; unknown ids are rejected so a typo never silently disables search. */
    public static List<SearchEngine> byIds(String commaSeparatedIds) {
        List<SearchEngine> engines = new ArrayList<>();
        for (String raw : commaSeparatedIds.split(",")) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            SearchEngine engine = catalog().stream().filter(e -> e.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown search engine: " + raw.trim()
                            + " (known: duckduckgo, bing, google)"));
            if (!engines.contains(engine)) engines.add(engine);
        }
        if (engines.isEmpty()) throw new IllegalArgumentException("No search engine configured");
        return List.copyOf(engines);
    }

    /**
     * A single-endpoint engine for tests and local search worlds: its start page with a search field named
     * {@code q} (or of type search); it is mechanically analysed.
     */
    public static SearchEngine custom(String homeUrl) {
        return new SearchEngine("custom", "Custom", List.of(new Endpoint(homeUrl, GENERIC_SEARCH_BOX, "", false)),
                Set.of());
    }
}
