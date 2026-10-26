package com.aresstack.argos.page;

import java.util.function.LongSupplier;

/**
 * Counts open requests from browser protocol events (request, finished, failed).
 * Observing the network this way is invisible to the page.
 */
public final class NetworkActivity {
    private final LongSupplier clock;
    private int inflight;
    private long lastActivity;

    public NetworkActivity(LongSupplier clockMillis) {
        this.clock = clockMillis;
        this.lastActivity = clockMillis.getAsLong();
    }

    public synchronized void started() {
        inflight++;
        lastActivity = clock.getAsLong();
    }

    public synchronized void ended() {
        inflight = Math.max(0, inflight - 1);
        lastActivity = clock.getAsLong();
    }

    /** A new top-level document discards requests of the previous one. */
    public synchronized void reset() {
        inflight = 0;
        lastActivity = clock.getAsLong();
    }

    public synchronized int inflight() {
        return inflight;
    }

    public synchronized long quietMillis() {
        return clock.getAsLong() - lastActivity;
    }
}
