package com.aresstack.argos.research;

/**
 * Why a research run ended (port of AskAI's {@code ResearchStopReason}, reduced to what Argos can decide
 * without a host state machine or a mandatory reranker). Every run ends with exactly one reason.
 */
public enum ResearchStopReason {
    /** Enough accepted sources on enough distinct sites: the normal end. */
    SUFFICIENT_EVIDENCE,
    PAGE_BUDGET_EXHAUSTED,
    SOURCE_BUDGET_EXHAUSTED,
    TIME_BUDGET_EXHAUSTED,
    /** Too many page reads failed in a row (browser gone, network down). */
    ERROR_BUDGET_EXHAUSTED,
    /** Every candidate was read or rejected before the evidence was sufficient. */
    NO_RELEVANT_PATHS,
    /** The search worked and honestly found nothing. */
    NO_SEARCH_RESULTS,
    /** Every search engine is waiting for a CAPTCHA the user has to solve; nothing was searched. */
    CHALLENGE_REQUIRED,
    /** The search failed technically. Not the same as an empty search. */
    SEARCH_FAILED,
    CANCELLED
}
