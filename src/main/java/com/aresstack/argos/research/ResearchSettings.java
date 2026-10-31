package com.aresstack.argos.research;

import com.aresstack.argos.EnvironmentVariables;

import java.time.Duration;
import java.util.Map;

/**
 * Whether the optional research orchestration is published, and its budget. Environment (all optional):
 * {@code RESEARCH_ENABLED} ({@code true} publishes web_research; default {@code false}),
 * {@code RESEARCH_MAX_PAGES}, {@code RESEARCH_MAX_SOURCES}, {@code RESEARCH_MIN_SOURCES},
 * {@code RESEARCH_MIN_SITES}, {@code RESEARCH_MAX_SECONDS}, {@code RESEARCH_MAX_SEARCHES},
 * {@code RESEARCH_LINKS_PER_PAGE}, {@code RESEARCH_LINK_DEPTH}.
 */
public record ResearchSettings(boolean enabled, ResearchBudget budget) {

    public static ResearchSettings disabled() {
        return new ResearchSettings(false, ResearchBudget.defaults());
    }

    public static ResearchSettings fromEnvironment() {
        return from(EnvironmentVariables.system());
    }

    static ResearchSettings from(Map<String, String> environment) {
        ResearchBudget defaults = ResearchBudget.defaults();
        String enabled = environment.getOrDefault("RESEARCH_ENABLED", "false").trim();
        if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("RESEARCH_ENABLED must be 'true' or 'false': " + enabled);
        }
        return new ResearchSettings(Boolean.parseBoolean(enabled), new ResearchBudget(
                integer(environment, "RESEARCH_MAX_SEARCHES", defaults.maxSearches()),
                integer(environment, "RESEARCH_MAX_PAGES", defaults.maxPagesRead()),
                integer(environment, "RESEARCH_MAX_SOURCES", defaults.maxSources()),
                defaults.maxConsecutiveErrors(),
                Duration.ofSeconds(integer(environment, "RESEARCH_MAX_SECONDS",
                        (int) defaults.maxDuration().toSeconds())),
                integer(environment, "RESEARCH_MIN_SOURCES", defaults.minimumSources()),
                integer(environment, "RESEARCH_MIN_SITES", defaults.minimumDistinctHosts()),
                integer(environment, "RESEARCH_LINKS_PER_PAGE", defaults.maxLinksPerPage()),
                integer(environment, "RESEARCH_LINK_DEPTH", defaults.maxLinkDepth())));
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
