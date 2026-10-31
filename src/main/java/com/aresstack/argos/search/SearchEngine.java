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
     * The engines of one web_search call. Without a selection ({@code null}) all available engines, in their
     * configured order. A selection searches only the named engines, in the order given, each once; it must name at
     * least one engine and only engines available here. A wrong id is rejected, never corrected or replaced by
     * another engine.
     *
     * @param requested engine ids of the call, or null when the call names none
     * @param available the configured engines (SEARCH_ENGINES), in their order
     */
    public static List<SearchEngine> select(List<String> requested, List<SearchEngine> available) {
        if (requested == null) return List.copyOf(available);
        String configured = String.join(", ", available.stream().map(SearchEngine::id).toList());
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("engines is empty. Name at least one engine (" + configured
                    + ") or leave engines out to search all of them.");
        }
        List<SearchEngine> selected = new ArrayList<>();
        for (String raw : requested) {
            String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            SearchEngine engine = available.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
            if (engine == null) {
                boolean known = catalog().stream().anyMatch(e -> e.id().equals(id));
                throw new IllegalArgumentException((known ? "Search engine not enabled here: " : "Unknown search engine: ")
                        + (raw == null ? "null" : "\"" + raw.trim() + "\"") + " (available: " + configured + ")");
            }
            if (!selected.contains(engine)) selected.add(engine);
        }
        return List.copyOf(selected);
    }

    /** JVM system property: the engines of a web_search call that names none (comma-separated ids). */
    public static final String DEFAULT_ENGINES_PROPERTY = "argos.search.defaultEngines";

    /**
     * The engines of a web_search call that names none: the value of {@link #DEFAULT_ENGINES_PROPERTY} when it is set,
     * a subset of the available engines in its own order; otherwise all available engines. A property that is set
     * but empty or names an engine that is not available is a configuration error, never a silent "all engines".
     *
     * @param property the property's value, or null when it is not set
     */
    public static List<SearchEngine> defaults(String property, List<SearchEngine> available) {
        if (property == null) return List.copyOf(available);
        List<String> ids = new ArrayList<>();
        for (String part : property.split(",")) if (!part.isBlank()) ids.add(part);
        try {
            return select(ids, available);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid -D" + DEFAULT_ENGINES_PROPERTY + "=" + property + ": "
                    + invalid.getMessage(), invalid);
        }
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
