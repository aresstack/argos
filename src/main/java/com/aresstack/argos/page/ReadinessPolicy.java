package com.aresstack.argos.page;

import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * When a page counts as ready to read. Every condition is observed from outside the
 * page (protocol events or Playwright's isolated utility world); none injects a
 * script into the page's own JavaScript world.
 *
 * <p>Network quiet comes from protocol events alone. The DOM conditions (stability,
 * text, loading indicators) need utility-world reads, and on Firefox BiDi every such
 * read currently grants the page transient user activation (aresstack/playwright4j#37).
 * Polling them would keep the page activated, so only {@code thorough} enables them.
 *
 * @param waitUntil          navigation load event: {@code commit}, {@code domcontentloaded} or {@code load}
 * @param timeoutMillis      settle budget after the load event; 0 disables settling (legacy behaviour)
 * @param pollMillis         sampling interval
 * @param domStableMillis    DOM fingerprint must be unchanged this long; 0 disables the check
 * @param networkQuietMillis no request may start or finish this long; 0 disables the check
 * @param maxInflightRequests requests that may remain open while "quiet" (long-polling, beacons)
 * @param minTextCharacters  visible text required before the page counts as having content; 0 disables
 * @param loadingSelectors   visible loading indicators that must disappear; empty disables the check
 */
public record ReadinessPolicy(String waitUntil, int timeoutMillis, int pollMillis, int domStableMillis,
                              int networkQuietMillis, int maxInflightRequests, int minTextCharacters,
                              List<String> loadingSelectors) {

    public static final List<String> DEFAULT_LOADING_SELECTORS = List.of(
            "[aria-busy='true']", "[role='progressbar']", ".spinner", ".loading-spinner", ".loader",
            ".skeleton", ".skeleton-loader", ".MuiCircularProgress-root", ".MuiSkeleton-root");

    public ReadinessPolicy {
        waitUntil = waitUntil == null ? "domcontentloaded" : waitUntil.trim().toLowerCase(Locale.ROOT);
        if (!List.of("commit", "domcontentloaded", "load").contains(waitUntil)) {
            throw new IllegalArgumentException("READY_WAIT_UNTIL must be commit, domcontentloaded or load");
        }
        timeoutMillis = clamp(timeoutMillis, 0, 60_000);
        pollMillis = clamp(pollMillis, 50, 2_000);
        domStableMillis = clamp(domStableMillis, 0, 30_000);
        networkQuietMillis = clamp(networkQuietMillis, 0, 30_000);
        maxInflightRequests = clamp(maxInflightRequests, 0, 100);
        minTextCharacters = clamp(minTextCharacters, 0, 100_000);
        loadingSelectors = loadingSelectors == null ? List.of() : List.copyOf(loadingSelectors);
    }

    /** DOMContentLoaded, then wait for a quiet network (≤ 8 s). Protocol events only, no page reads. */
    public static ReadinessPolicy balanced() {
        return new ReadinessPolicy("domcontentloaded", 8_000, 250, 0, 750, 2, 0, List.of());
    }

    /** Previous Argos behaviour: return right after DOMContentLoaded. */
    public static ReadinessPolicy fast() {
        return new ReadinessPolicy("domcontentloaded", 0, 250, 0, 0, 2, 0, List.of());
    }

    /**
     * Full load event, quiet network, stable DOM, visible text and no spinner. Polls the DOM,
     * which grants user activation on Firefox BiDi until aresstack/playwright4j#37 is fixed.
     */
    public static ReadinessPolicy thorough() {
        return new ReadinessPolicy("load", 15_000, 250, 1_500, 1_000, 0, 1, DEFAULT_LOADING_SELECTORS);
    }

    public boolean settles() {
        return timeoutMillis > 0;
    }

    /** Whether waiting needs DOM reads; without them readiness uses protocol events only. */
    public boolean needsDomReads() {
        return domStableMillis > 0 || minTextCharacters > 0 || !loadingSelectors.isEmpty();
    }

    /** Same conditions with another settle budget (used by browser_wait). */
    public ReadinessPolicy withTimeout(int millis) {
        return new ReadinessPolicy(waitUntil, millis, pollMillis, domStableMillis, networkQuietMillis,
                maxInflightRequests, minTextCharacters, loadingSelectors);
    }

    /**
     * Reads {@code PAGE_READINESS} (fast | balanced | thorough) and optional
     * per-condition overrides {@code READY_*}.
     */
    public static ReadinessPolicy from(UnaryOperator<String> setting) {
        String preset = setting.apply("PAGE_READINESS");
        ReadinessPolicy base = switch (preset == null ? "balanced" : preset.trim().toLowerCase(Locale.ROOT)) {
            case "balanced" -> balanced();
            case "fast" -> fast();
            case "thorough" -> thorough();
            default -> throw new IllegalArgumentException(
                    "PAGE_READINESS must be fast, balanced or thorough: " + preset);
        };
        String selectors = setting.apply("READY_LOADING_SELECTORS");
        List<String> loading = selectors == null ? base.loadingSelectors()
                : "none".equalsIgnoreCase(selectors.trim()) ? List.of()
                : List.of(selectors.split("\\s*\\|\\s*")).stream().filter(s -> !s.isBlank()).toList();
        String waitUntil = setting.apply("READY_WAIT_UNTIL");
        return new ReadinessPolicy(
                waitUntil == null ? base.waitUntil() : waitUntil,
                integer(setting, "READY_TIMEOUT_MS", base.timeoutMillis()),
                integer(setting, "READY_POLL_MS", base.pollMillis()),
                integer(setting, "READY_DOM_STABLE_MS", base.domStableMillis()),
                integer(setting, "READY_NETWORK_QUIET_MS", base.networkQuietMillis()),
                integer(setting, "READY_MAX_INFLIGHT", base.maxInflightRequests()),
                integer(setting, "READY_MIN_TEXT", base.minTextCharacters()),
                loading);
    }

    private static int integer(UnaryOperator<String> setting, String name, int fallback) {
        String value = setting.apply(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer: " + value, invalid);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
