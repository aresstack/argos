package com.aresstack.argos.search;

/**
 * Honest outcome of one result page. EXTRACTION_FAILED (layout not understood) is never reported as
 * NO_ORGANIC_RESULTS (the engine explicitly found nothing): only the latter ends an engine's turn as
 * "empty"; the former moves on to the fallback engine.
 */
public enum SearchOutcome {
    ORGANIC_RESULTS,
    NO_ORGANIC_RESULTS,
    EXTRACTION_FAILED
}
