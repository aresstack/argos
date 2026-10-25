package com.aresstack.argos.page;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadinessWaiterTest {
    private final AtomicLong clock = new AtomicLong(1_000);
    private final ReadinessWaiter waiter = new ReadinessWaiter(clock::get, millis -> clock.addAndGet(millis));

    private static ReadinessPolicy policy(int timeout, int domStable, int quiet, int minText, List<String> loading) {
        return new ReadinessPolicy("domcontentloaded", timeout, 100, domStable, quiet, 0, minText, loading);
    }

    /** Replays samples; the last one repeats. */
    private static ReadinessWaiter.Probe script(PageSample... samples) {
        Deque<PageSample> queue = new ArrayDeque<>(List.of(samples));
        return () -> queue.size() > 1 ? queue.poll() : queue.peek();
    }

    private static PageSample sample(int elements, int text, boolean loading, int inflight, long quiet) {
        return new PageSample(elements, text, loading, true, inflight, quiet);
    }

    @Test
    void skipsWhenSettlingIsDisabled() throws Exception {
        ReadinessReport report = waiter.await(ReadinessPolicy.fast(), false, () -> {
            throw new AssertionError("must not sample");
        });
        assertEquals("skipped", report.state());
        assertEquals(1_000, clock.get());
    }

    @Test
    void readyOnceDomStopsChanging() throws Exception {
        ReadinessReport report = waiter.await(policy(5_000, 300, 0, 0, List.of()), false, script(
                sample(10, 5, false, 0, 0), sample(20, 50, false, 0, 0), sample(30, 90, false, 0, 0),
                sample(30, 90, false, 0, 0)));
        assertEquals(ReadinessReport.READY, report.state());
        assertEquals(List.of(ReadinessWaiter.DOM_STABLE), report.met());
        // Stable from t=200 on; 300 ms of stability reached at t=500.
        assertEquals(500, report.waitedMs());
    }

    @Test
    void waitsForSpinnerToDisappearAndContentToArrive() throws Exception {
        ReadinessReport report = waiter.await(policy(5_000, 0, 0, 10, List.of(".spinner")), false, script(
                sample(5, 8, true, 0, 0), sample(5, 8, true, 0, 0), sample(9, 40, false, 0, 0)));
        assertEquals(ReadinessReport.READY, report.state());
        assertEquals(200, report.waitedMs());
        assertTrue(report.met().containsAll(List.of(ReadinessWaiter.NO_LOADING_INDICATOR, ReadinessWaiter.CONTENT_PRESENT)));
    }

    @Test
    void waitsForNetworkQuiet() throws Exception {
        ReadinessReport report = waiter.await(policy(5_000, 0, 500, 0, List.of()), false, script(
                sample(1, 1, false, 3, 0), sample(1, 1, false, 0, 100), sample(1, 1, false, 0, 600)));
        assertEquals(ReadinessReport.READY, report.state());
        assertEquals(List.of(ReadinessWaiter.NETWORK_QUIET), report.met());
    }

    @Test
    void timesOutWithoutFailingAndNamesPendingConditions() throws Exception {
        ReadinessReport report = waiter.await(policy(1_000, 0, 500, 1, List.of(".loader")), false,
                script(sample(1, 50, true, 1, 0)));
        assertEquals(ReadinessReport.TIMEOUT, report.state());
        assertEquals(1_000, report.waitedMs());
        assertEquals(List.of(ReadinessWaiter.CONTENT_PRESENT), report.met());
        assertEquals(List.of(ReadinessWaiter.NETWORK_QUIET, ReadinessWaiter.NO_LOADING_INDICATOR), report.pending());
    }

    @Test
    void awaitsTargetElement() throws Exception {
        ReadinessReport report = waiter.await(policy(2_000, 0, 0, 0, List.of()), true, script(
                new PageSample(1, 1, false, false, 0, 0), new PageSample(1, 1, false, true, 0, 0)));
        assertEquals(ReadinessReport.READY, report.state());
        assertEquals(List.of(ReadinessWaiter.TARGET_VISIBLE), report.met());
    }

    @Test
    void reportsInvalidSelectorInsteadOfTimingOut() {
        RuntimeException invalid = new RuntimeException(
                "Error: Unexpected token \"[\" while parsing css selector \"div[\". Did you mean to CSS.escape it?");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> waiter.await(policy(5_000, 0, 0, 0, List.of()), true, () -> { throw invalid; }));
        assertSame(invalid, thrown);
        assertEquals(1_000, clock.get(), "must fail on the first sample, not after the budget");
    }

    @Test
    void reportsClosedPageAndProtocolErrors() {
        assertThrows(IllegalStateException.class, () -> waiter.await(policy(5_000, 200, 0, 0, List.of()), false,
                () -> { throw new IllegalStateException("Target page, context or browser has been closed"); }));
        assertThrows(IllegalStateException.class, () -> waiter.await(policy(5_000, 200, 0, 0, List.of()), false,
                () -> { throw new IllegalStateException("Protocol error (script.callFunction): unknown error"); }));
    }

    @Test
    void recognisesNavigationFailuresAlsoAsCause() {
        assertTrue(ReadinessWaiter.isNavigationFailure(new IllegalStateException("wrapped",
                new RuntimeException("Execution context was destroyed, most likely because of a navigation"))));
        assertFalse(ReadinessWaiter.isNavigationFailure(new RuntimeException("Timeout 2000ms exceeded")));
        assertFalse(ReadinessWaiter.isNavigationFailure(new RuntimeException((String) null)));
    }

    @Test
    void treatsSampleFailuresDuringNavigationAsUnstable() throws Exception {
        int[] calls = {0};
        ReadinessReport report = waiter.await(policy(2_000, 200, 0, 0, List.of()), false, () -> {
            if (calls[0]++ < 2) throw new IllegalStateException("Execution context was destroyed");
            return sample(4, 4, false, 0, 0);
        });
        assertEquals(ReadinessReport.READY, report.state());
        assertEquals(400, report.waitedMs());
    }
}
