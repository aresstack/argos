package com.aresstack.argos.page;

import java.util.List;

/**
 * Outcome of waiting for page readiness. A timeout is not an error: the page may
 * still be readable, so callers report which conditions were not met.
 *
 * @param state   {@code ready}, {@code timeout}, {@code skipped} or {@code static-document}
 * @param waitedMs time spent settling after the load event
 * @param met     satisfied conditions
 * @param pending conditions still unsatisfied when the wait ended
 */
public record ReadinessReport(String state, long waitedMs, List<String> met, List<String> pending) {
    public static final String READY = "ready";
    public static final String TIMEOUT = "timeout";

    public static ReadinessReport skipped() {
        return new ReadinessReport("skipped", 0, List.of(), List.of());
    }

    /** XML, JSON, text and binary documents are complete once delivered. */
    public static ReadinessReport staticDocument() {
        return new ReadinessReport("static-document", 0, List.of("document-loaded"), List.of());
    }

    public boolean ready() {
        return !TIMEOUT.equals(state);
    }
}
