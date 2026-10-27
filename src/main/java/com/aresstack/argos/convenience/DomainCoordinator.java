package com.aresstack.argos.convenience;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The process-wide {@link NavigationGate}: at most one Argos navigation per registrable domain at a time, and at
 * most {@code maxActive} navigations overall, across batches, tool calls and chats (each chat has its own browser,
 * all share this coordinator). A domain that answered 429 or 403 or showed a challenge cools down: Retry-After when
 * the site sent one, else 60 s doubling per further refusal up to 15 min; a delivered page resets it. Nothing is
 * sent to probe a site, and nothing tries to get around a refusal.
 */
public final class DomainCoordinator implements NavigationGate {
    public static final int DEFAULT_MAX_ACTIVE = 4;
    static final long BASE_COOLDOWN_MILLIS = 60_000;
    static final long MAX_COOLDOWN_MILLIS = 15 * 60_000;

    private static final DomainCoordinator SHARED = new DomainCoordinator(DEFAULT_MAX_ACTIVE, System::currentTimeMillis);

    private final int maxActive;
    private final LongSupplier clock;
    private final Set<String> active = new HashSet<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();
    private final Map<String, Integer> refusals = new HashMap<>();

    public DomainCoordinator(int maxActive, LongSupplier clock) {
        this.maxActive = Math.max(1, maxActive);
        this.clock = clock;
    }

    /** The one coordinator of this Argos process. */
    public static DomainCoordinator shared() {
        return SHARED;
    }

    @Override
    public synchronized Lease tryAcquire(String domain) {
        String key = domain == null ? "" : domain;
        if (active.contains(key) || active.size() >= maxActive) return null;
        active.add(key);
        return new Lease() {
            private boolean released;

            @Override public String domain() { return key; }

            @Override public void release(int status, long retryAfterSeconds, boolean challenge) {
                synchronized (DomainCoordinator.this) {
                    if (released) return;
                    released = true;
                    active.remove(key);
                    answered(key, status, retryAfterSeconds, challenge);
                    DomainCoordinator.this.notifyAll();
                }
            }
        };
    }

    /**
     * Waits (on the calling thread, never the browser driver) until {@code domain} has a free slot, at most until
     * {@code deadlineMillis} of this coordinator's clock. @return the lease, or null at the deadline
     */
    public Lease acquire(String domain, long deadlineMillis) throws InterruptedException {
        synchronized (this) {
            while (true) {
                Lease lease = tryAcquire(domain);
                if (lease != null) return lease;
                long wait = deadlineMillis - clock.getAsLong();
                if (wait <= 0) return null;
                wait(Math.min(wait, 100));
            }
        }
    }

    @Override
    public synchronized long cooldownMillis(String domain) {
        Long until = blockedUntil.get(domain);
        if (until == null) return 0;
        long left = until - clock.getAsLong();
        if (left > 0) return left;
        blockedUntil.remove(domain);
        return 0;
    }

    /** Domains with a navigation in progress (tests and diagnostics). */
    public synchronized Set<String> activeDomains() {
        return Set.copyOf(active);
    }

    private void answered(String domain, int status, long retryAfterSeconds, boolean challenge) {
        if (challenge || status == 429 || status == 403) {
            int count = refusals.merge(domain, 1, Integer::sum);
            long cooldown = Math.min(MAX_COOLDOWN_MILLIS, BASE_COOLDOWN_MILLIS << Math.min(10, count - 1));
            if (retryAfterSeconds > 0) cooldown = Math.min(MAX_COOLDOWN_MILLIS * 4, retryAfterSeconds * 1000);
            blockedUntil.put(domain, clock.getAsLong() + cooldown);
        } else if (status > 0 && status < 400) {
            refusals.remove(domain);
            blockedUntil.remove(domain);
        }
    }
}
