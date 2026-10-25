package com.aresstack.argos.research;

import java.time.Duration;

/**
 * The hard limits of one research run (port of AskAI's {@code ResearchRunBudget}). Defaults are smaller
 * than AskAI's 20 pages / 10 minutes because a run is a single MCP tool call, and MCP clients time out.
 *
 * @param maxSearches          searches per run (one per planned query)
 * @param maxPagesRead         navigations per run, redirects and rejected pages included
 * @param maxSources           accepted sources at which the run stops even without host diversity
 * @param maxConsecutiveErrors failed page reads in a row before the run gives up
 * @param maxDuration          wall time of the run
 * @param minimumSources       sufficiency: at least this many accepted sources …
 * @param minimumDistinctHosts … on at least this many different sites
 * @param maxLinksPerPage      links of one relevant page that may enter the frontier
 * @param maxLinkDepth         0 = only search hits, 1 = their links, 2 = links of those pages
 */
public record ResearchBudget(int maxSearches, int maxPagesRead, int maxSources, int maxConsecutiveErrors,
                             Duration maxDuration, int minimumSources, int minimumDistinctHosts,
                             int maxLinksPerPage, int maxLinkDepth) {

    public ResearchBudget {
        maxSearches = clamp(maxSearches, 1, 5);
        maxPagesRead = clamp(maxPagesRead, 1, 50);
        maxSources = clamp(maxSources, 1, 20);
        maxConsecutiveErrors = clamp(maxConsecutiveErrors, 1, 10);
        if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("Research duration must be positive");
        }
        minimumSources = clamp(minimumSources, 1, maxSources);
        minimumDistinctHosts = clamp(minimumDistinctHosts, 1, minimumSources);
        maxLinksPerPage = clamp(maxLinksPerPage, 0, 10);
        maxLinkDepth = clamp(maxLinkDepth, 0, 3);
    }

    public static ResearchBudget defaults() {
        return new ResearchBudget(2, 8, 5, 3, Duration.ofSeconds(120), 3, 2, 3, 2);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
