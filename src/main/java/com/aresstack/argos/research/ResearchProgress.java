package com.aresstack.argos.research;

/** Counters of a run at one moment; immutable snapshot handed to listeners and the report. */
public record ResearchProgress(int searches, int pagesRead, int sources, int distinctHosts,
                               int consecutiveErrors, int errors, int rejectedPages, int blockedPages,
                               int duplicates, long elapsedMillis) {
}
