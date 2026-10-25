package com.aresstack.argos.page;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Polls page samples until every enabled readiness condition holds or the budget ends.
 * Uses no fixed sleeps beyond the poll interval and returns as soon as the page settles.
 */
public final class ReadinessWaiter {
    public static final String DOM_STABLE = "dom-stable";
    public static final String NETWORK_QUIET = "network-quiet";
    public static final String NO_LOADING_INDICATOR = "no-loading-indicator";
    public static final String CONTENT_PRESENT = "content-present";
    public static final String TARGET_VISIBLE = "target-visible";

    /** Takes one sample; may throw while the page navigates. */
    @FunctionalInterface
    public interface Probe {
        PageSample sample() throws Exception;
    }

    /** Pauses between samples; the browser adapter must keep processing protocol events meanwhile. */
    @FunctionalInterface
    public interface Pause {
        void pause(int millis) throws Exception;
    }

    private final LongSupplier clock;
    private final Pause pause;

    public ReadinessWaiter(LongSupplier clockMillis, Pause pause) {
        this.clock = clockMillis;
        this.pause = pause;
    }

    public ReadinessReport await(ReadinessPolicy policy, boolean awaitingTarget, Probe probe) throws Exception {
        if (!policy.settles()) return ReadinessReport.skipped();
        long start = clock.getAsLong();
        PageSample previous = null;
        long domStableSince = start;
        List<String> pending = List.of();
        List<String> met = List.of();
        while (true) {
            long now = clock.getAsLong();
            PageSample sample;
            try {
                sample = probe.sample();
            } catch (Exception navigating) {
                sample = null; // execution context replaced; treat as unstable
            }
            if (sample == null || !sample.sameDomAs(previous)) domStableSince = now;
            previous = sample;
            met = new ArrayList<>();
            pending = new ArrayList<>();
            if (sample != null) {
                check(policy.domStableMillis() > 0, now - domStableSince >= policy.domStableMillis(),
                        DOM_STABLE, met, pending);
                check(policy.networkQuietMillis() > 0,
                        sample.inflightRequests() <= policy.maxInflightRequests()
                                && sample.quietMillis() >= policy.networkQuietMillis(),
                        NETWORK_QUIET, met, pending);
                check(!policy.loadingSelectors().isEmpty(), !sample.loadingVisible(),
                        NO_LOADING_INDICATOR, met, pending);
                check(policy.minTextCharacters() > 0, sample.textLength() >= policy.minTextCharacters(),
                        CONTENT_PRESENT, met, pending);
                check(awaitingTarget, sample.targetPresent(), TARGET_VISIBLE, met, pending);
                if (pending.isEmpty()) {
                    return new ReadinessReport(ReadinessReport.READY, now - start, List.copyOf(met), List.of());
                }
            } else {
                pending.add("page-available");
            }
            long remaining = policy.timeoutMillis() - (now - start);
            if (remaining <= 0) {
                return new ReadinessReport(ReadinessReport.TIMEOUT, now - start, List.copyOf(met),
                        List.copyOf(pending));
            }
            pause.pause((int) Math.min(policy.pollMillis(), remaining));
        }
    }

    private static void check(boolean enabled, boolean satisfied, String name,
                              List<String> met, List<String> pending) {
        if (!enabled) return;
        (satisfied ? met : pending).add(name);
    }
}
