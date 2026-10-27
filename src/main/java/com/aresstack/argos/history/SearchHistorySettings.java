package com.aresstack.argos.history;

import java.util.Map;

/**
 * Search History switches: {@code ARGOS_SEARCH_HISTORY} (default true; only effective with per-chat profiles),
 * {@code ARGOS_SEARCH_HISTORY_HTML} (single-file HTML archive, default true) and
 * {@code ARGOS_SEARCH_HISTORY_HTML_TIMEOUT_MS} (collector budget, default 15000).
 */
public record SearchHistorySettings(boolean enabled, boolean archiveHtml, int collectorTimeoutMillis) {

    public static SearchHistorySettings fromEnvironment() {
        return from(System.getenv());
    }

    static SearchHistorySettings from(Map<String, String> environment) {
        int timeout = HtmlCollector.DEFAULT_TIMEOUT_MILLIS;
        String configured = environment.getOrDefault("ARGOS_SEARCH_HISTORY_HTML_TIMEOUT_MS", "").strip();
        if (!configured.isEmpty()) {
            if (!configured.matches("\\d{1,6}")) {
                throw new IllegalArgumentException("ARGOS_SEARCH_HISTORY_HTML_TIMEOUT_MS must be milliseconds: "
                        + configured);
            }
            timeout = Math.max(1_000, Math.min(60_000, Integer.parseInt(configured)));
        }
        return new SearchHistorySettings(flag(environment, "ARGOS_SEARCH_HISTORY"),
                flag(environment, "ARGOS_SEARCH_HISTORY_HTML"), timeout);
    }

    private static boolean flag(Map<String, String> environment, String name) {
        String value = environment.getOrDefault(name, "true").strip();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(name + " must be 'true' or 'false': " + value);
        }
        return Boolean.parseBoolean(value);
    }
}
