package com.aresstack.argos.web;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Paces navigations per host so research tabs never hit one site in a burst: two navigations to the same host
 * start at least {@code spacingMillis} apart, and a host that answered 429 or 403 is left alone for a cooldown
 * that doubles with every further refusal (reset by a delivered page). Hosts are compared exactly (lower case);
 * nothing is sent to find out whether a site would accept more. This lowers the risk of a block, it cannot
 * prevent one.
 */
final class HostThrottle {
    /** Time source and waiting, replaceable in tests. */
    interface Clock {
        long millis();

        void sleep(long millis) throws InterruptedException;
    }

    static final Clock SYSTEM = new Clock() {
        @Override public long millis() { return System.currentTimeMillis(); }

        @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
    };

    static final long DEFAULT_SPACING_MILLIS = 1_500;
    static final long DEFAULT_COOLDOWN_MILLIS = 60_000;
    static final long MAX_COOLDOWN_MILLIS = 15 * 60_000;

    private final Clock clock;
    private final long spacingMillis;
    private final long cooldownMillis;
    private final Map<String, Long> lastStart = new HashMap<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();
    private final Map<String, Integer> refusals = new HashMap<>();

    HostThrottle(Clock clock, long spacingMillis, long cooldownMillis) {
        this.clock = clock;
        this.spacingMillis = spacingMillis;
        this.cooldownMillis = cooldownMillis;
    }

    static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException unparsable) {
            return "";
        }
    }

    /** Milliseconds the host still cools down after a refusal; 0 when it may be opened. */
    synchronized long cooldown(String host) {
        Long until = blockedUntil.get(host);
        return until == null ? 0 : Math.max(0, until - clock.millis());
    }

    /** Waits until each host's spacing has passed, then records the start for all of them. */
    void awaitTurn(Collection<String> hosts) {
        long wait;
        synchronized (this) {
            long now = clock.millis();
            wait = 0;
            for (String host : hosts) {
                Long last = lastStart.get(host);
                if (last != null) wait = Math.max(wait, last + spacingMillis - now);
            }
        }
        if (wait > 0) {
            try {
                clock.sleep(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            long now = clock.millis();
            hosts.forEach(host -> lastStart.put(host, now));
        }
    }

    /** Records the status the host answered with: 429/403 start or extend its cooldown, a page resets it. */
    synchronized void answered(String host, int status) {
        if (status == 429 || status == 403) {
            int count = refusals.merge(host, 1, Integer::sum);
            long cooldown = Math.min(MAX_COOLDOWN_MILLIS, cooldownMillis << Math.min(10, count - 1));
            blockedUntil.put(host, clock.millis() + cooldown);
        } else if (status > 0 && status < 400) {
            refusals.remove(host);
            blockedUntil.remove(host);
        }
    }
}
