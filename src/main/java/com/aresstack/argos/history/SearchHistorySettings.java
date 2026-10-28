package com.aresstack.argos.history;

import java.util.EnumMap;
import java.util.Map;

/**
 * Search History switches: {@code ARGOS_SEARCH_HISTORY} (default true; only effective with per-chat profiles), one
 * {@code ARGOS_SEARCH_HISTORY_CAPTURE_*} switch per optional resource category (each default false, see
 * {@link ResourceCategory}) and {@code ARGOS_SEARCH_HISTORY_HTML_TIMEOUT_MS} (budget of the explicit
 * browser_archive_html collector, default 15000; no activation switch). The former {@code ARGOS_SEARCH_HISTORY_HTML}
 * has no effect any more: the single-file archive is only made by the explicit tool call.
 *
 * @param captureDefaults the server-wide starting value of each optional category; a chat may override them
 *                        ({@link CaptureSettings})
 */
public record SearchHistorySettings(boolean enabled, Map<ResourceCategory, Boolean> captureDefaults,
                                    int collectorTimeoutMillis) {
    public SearchHistorySettings {
        EnumMap<ResourceCategory, Boolean> all = new EnumMap<>(ResourceCategory.class);
        for (ResourceCategory category : ResourceCategory.values()) {
            if (category != ResourceCategory.CONTENT) {
                all.put(category, captureDefaults != null && Boolean.TRUE.equals(captureDefaults.get(category)));
            }
        }
        captureDefaults = Map.copyOf(all);
    }

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
        Map<ResourceCategory, Boolean> defaults = new EnumMap<>(ResourceCategory.class);
        for (ResourceCategory category : ResourceCategory.values()) {
            if (category.variable != null) defaults.put(category, flag(environment, category.variable, false));
        }
        if (environment.containsKey("ARGOS_SEARCH_HISTORY_HTML")) {
            System.out.println("[argos-history] ARGOS_SEARCH_HISTORY_HTML has no effect any more: single-file HTML "
                    + "archives are only made by an explicit browser_archive_html call.");
        }
        return new SearchHistorySettings(flag(environment, "ARGOS_SEARCH_HISTORY", true), defaults, timeout);
    }

    private static boolean flag(Map<String, String> environment, String name, boolean fallback) {
        String value = environment.getOrDefault(name, "").strip();
        if (value.isEmpty() && !environment.containsKey(name)) return fallback;
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(name + " must be 'true' or 'false': " + value);
        }
        return Boolean.parseBoolean(value);
    }
}
