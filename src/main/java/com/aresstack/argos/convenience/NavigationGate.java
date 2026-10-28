package com.aresstack.argos.convenience;

/**
 * Admission of browser navigations per registrable domain. The browser asks before it starts a navigation and
 * reports the answer afterwards; it never waits inside the gate (the driver thread keeps serving other pages).
 */
public interface NavigationGate {
    /** A granted navigation slot; released exactly once with what the site answered. */
    interface Lease {
        String domain();

        /**
         * @param status            HTTP status of the main document, 0 when unknown or failed
         * @param retryAfterSeconds the Retry-After the site sent, 0 when none
         * @param challenge         true when the page is a recognizable access challenge
         */
        void release(int status, long retryAfterSeconds, boolean challenge);
    }

    /** A slot for {@code domain} now, or null while another navigation of it (or the global limit) is active. */
    Lease tryAcquire(String domain);

    /** Milliseconds the domain still cools down after a refusal (429, 403, challenge); 0 when it may be opened. */
    long cooldownMillis(String domain);

    /** A gate that admits everything (tests, tools outside the convenience profile). */
    NavigationGate OPEN = new NavigationGate() {
        @Override public Lease tryAcquire(String domain) {
            return new Lease() {
                @Override public String domain() { return domain; }

                @Override public void release(int status, long retryAfterSeconds, boolean challenge) { }
            };
        }

        @Override public long cooldownMillis(String domain) { return 0; }
    };
}
