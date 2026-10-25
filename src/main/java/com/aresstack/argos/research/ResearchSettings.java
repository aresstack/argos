package com.aresstack.argos.research;

import java.time.Duration;
import java.util.Map;

/**
 * Whether the optional research orchestration is published, and its budget. Environment (all optional):
 * {@code ARGOS_RESEARCH} ({@code true} publishes web_research; default {@code false}),
 * {@code ARGOS_RESEARCH_MAX_PAGES}, {@code ARGOS_RESEARCH_MAX_SOURCES}, {@code ARGOS_RESEARCH_MIN_SOURCES},
 * {@code ARGOS_RESEARCH_MIN_SITES}, {@code ARGOS_RESEARCH_MAX_SECONDS}, {@code ARGOS_RESEARCH_MAX_SEARCHES},
 * {@code ARGOS_RESEARCH_LINKS_PER_PAGE}, {@code ARGOS_RESEARCH_LINK_DEPTH}.
 */
public record ResearchSettings(boolean enabled, ResearchBudget budget) {

    public static ResearchSettings disabled() {
        return new ResearchSettings(false, ResearchBudget.defaults());
    }

    public static ResearchSettings fromEnvironment() {
        return from(System.getenv());
    }

    static ResearchSettings from(Map<String, String> environment) {
        ResearchBudget defaults = ResearchBudget.defaults();
        String enabled = environment.getOrDefault("ARGOS_RESEARCH", "false").trim();
        if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("ARGOS_RESEARCH must be 'true' or 'false': " + enabled);
        }
        return new ResearchSettings(Boolean.parseBoolean(enabled), new ResearchBudget(
                integer(environment, "ARGOS_RESEARCH_MAX_SEARCHES", defaults.maxSearches()),
                integer(environment, "ARGOS_RESEARCH_MAX_PAGES", defaults.maxPagesRead()),
                integer(environment, "ARGOS_RESEARCH_MAX_SOURCES", defaults.maxSources()),
                defaults.maxConsecutiveErrors(),
                Duration.ofSeconds(integer(environment, "ARGOS_RESEARCH_MAX_SECONDS",
                        (int) defaults.maxDuration().toSeconds())),
                integer(environment, "ARGOS_RESEARCH_MIN_SOURCES", defaults.minimumSources()),
                integer(environment, "ARGOS_RESEARCH_MIN_SITES", defaults.minimumDistinctHosts()),
                integer(environment, "ARGOS_RESEARCH_LINKS_PER_PAGE", defaults.maxLinksPerPage()),
                integer(environment, "ARGOS_RESEARCH_LINK_DEPTH", defaults.maxLinkDepth())));
    }

    private static int integer(Map<String, String> environment, String name, int fallback) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer: " + value);
        }
    }
}
