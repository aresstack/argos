package com.aresstack.argos.search;

import java.time.Duration;
import java.util.List;

/**
 * Runtime settings of web_search. Environment: {@code ARGOS_SEARCH_ENGINES} (comma-separated order,
 * default {@code duckduckgo,bing,google}), {@code ARGOS_SEARCH_MAX_RESULTS} (default 10, max 30) and
 * {@code ARGOS_SEARCH_CONSENT_ACCEPT} (default false).
 *
 * @param challengeLock     how long an engine family that showed a CAPTCHA is skipped
 * @param acceptAllConsent  explicit opt-in to click "accept all" when a banner offers nothing else;
 *                          without it such an engine is skipped as CONSENT_REQUIRED
 */
public record SearchSettings(List<SearchEngine> engines, int maxResults, Duration challengeLock,
                             boolean acceptAllConsent) {
    public static final String DEFAULT_ENGINES = "duckduckgo,bing,google";

    public SearchSettings {
        engines = List.copyOf(engines);
        if (engines.isEmpty()) throw new IllegalArgumentException("At least one search engine is required");
        maxResults = Math.max(1, Math.min(30, maxResults));
    }

    public SearchSettings(List<SearchEngine> engines, int maxResults, Duration challengeLock) {
        this(engines, maxResults, challengeLock, false);
    }

    public static SearchSettings defaults() {
        return new SearchSettings(SearchEngine.byIds(DEFAULT_ENGINES), 10, Duration.ofMinutes(10));
    }

    public static SearchSettings fromEnvironment() {
        String engines = System.getenv("ARGOS_SEARCH_ENGINES");
        String max = System.getenv("ARGOS_SEARCH_MAX_RESULTS");
        int maxResults = 10;
        if (max != null && !max.isBlank()) {
            try {
                maxResults = Integer.parseInt(max.trim());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("ARGOS_SEARCH_MAX_RESULTS must be an integer: " + max);
            }
        }
        return new SearchSettings(SearchEngine.byIds(engines == null || engines.isBlank() ? DEFAULT_ENGINES : engines),
                maxResults, Duration.ofMinutes(10),
                Boolean.parseBoolean(System.getenv().getOrDefault("ARGOS_SEARCH_CONSENT_ACCEPT", "false").trim()));
    }
}
