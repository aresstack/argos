package com.aresstack.argos;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The environment as Argos reads it: configuration variables without the former {@code ARGOS_} prefix
 * ({@code TOOL_PROFILE}, {@code PROFILE_ISOLATION}, ...; issue #59, after {@code BROWSER_DIR} and
 * {@code BROWSER_LAUNCH_CONFIG} in #56).
 *
 * <p>During the migration the old names stay deprecated aliases, so an existing AresStack webserver configuration keeps
 * working: an alias fills in its new name, the new name wins over it, and every alias found is reported once at startup
 * with its name only, never its value (an upstream proxy URL can carry a password). The aliases are removed in a
 * separate breaking change once the installations have moved to the new names.
 *
 * <p>Not aliased: {@code ARGOS_RUNTIME_OK} (a self-test marker, no variable), the retired
 * {@code ARGOS_ALLOW_PRIVATE_NETWORKS} and {@code ARGOS_SEARCH_HISTORY_HTML} (read under their old names only to
 * explain that they are gone) and the browser settings #56 renamed without an alias.
 */
public final class EnvironmentVariables {
    /** Every configuration variable Argos reads, apart from the already prefix-free server and browser settings. */
    static final List<String> NAMES = List.of(
            "PROFILE_ISOLATION", "CHAT_PROFILES_DIR", "MAX_BROWSERS", "BROWSER_IDLE_MINUTES",
            "BROWSER_PROFILE_DIR", "BROWSER_HEADLESS", "CAMOUFOX_DISTRIBUTION_URL", "TOOL_PROFILE",
            "NETWORK_POLICY", "INTRANET_ALLOW", "UPSTREAM_PROXY", "UPSTREAM_DNS", "NETWORK_BODIES",
            "PAGE_READINESS", "READY_WAIT_UNTIL", "READY_TIMEOUT_MS", "READY_POLL_MS", "READY_DOM_STABLE_MS",
            "READY_NETWORK_QUIET_MS", "READY_MAX_INFLIGHT", "READY_MIN_TEXT", "READY_LOADING_SELECTORS",
            "HUMAN_INPUT", "HUMAN_SPEED", "HUMAN_TYPO_RATE", "HUMAN_SEED", "INPUT_UNICODE",
            "SEARCH_ENGINES", "SEARCH_MAX_RESULTS", "SEARCH_CONSENT_ACCEPT", "CONVENIENCE_RESULTS_PER_ENGINE",
            "RESEARCH_ENABLED", "RESEARCH_MAX_SEARCHES", "RESEARCH_MAX_PAGES", "RESEARCH_MAX_SOURCES",
            "RESEARCH_MIN_SOURCES", "RESEARCH_MIN_SITES", "RESEARCH_MAX_SECONDS", "RESEARCH_LINKS_PER_PAGE",
            "RESEARCH_LINK_DEPTH",
            "SEARCH_HISTORY_ENABLED", "SEARCH_HISTORY_HTML_TIMEOUT_MS", "SEARCH_HISTORY_EXPORT_TTL_MINUTES",
            "SEARCH_HISTORY_CAPTURE_IMAGES", "SEARCH_HISTORY_CAPTURE_FONTS", "SEARCH_HISTORY_CAPTURE_AUDIO",
            "SEARCH_HISTORY_CAPTURE_VIDEO", "SEARCH_HISTORY_CAPTURE_SCRIPTS", "SEARCH_HISTORY_CAPTURE_STYLES",
            "SEARCH_HISTORY_CAPTURE_PDF", "SEARCH_HISTORY_CAPTURE_OTHER");

    /** Deprecated alias → new name: {@code ARGOS_} + the new name, except where the bare name would be too generic. */
    static final Map<String, String> LEGACY_ALIASES = legacyAliases();

    private static volatile Map<String, String> system;

    private EnvironmentVariables() {
    }

    /** The process environment with the aliases resolved; deprecations are reported once, on first use. */
    public static Map<String, String> system() {
        Map<String, String> resolved = system;
        if (resolved == null) {
            synchronized (EnvironmentVariables.class) {
                if (system == null) system = canonical(System.getenv(), System.err::println);
                resolved = system;
            }
        }
        return resolved;
    }

    /** {@link #canonical(Map, Consumer)} without reporting, for tests. */
    public static Map<String, String> canonical(Map<String, String> environment) {
        return canonical(environment, warning -> { });
    }

    /**
     * Resolves the deprecated aliases of {@code environment}: an alias sets its new name unless the new name has a
     * value, and is removed. Reports each alias by name to {@code warnings}, and whether it conflicts with the new name.
     */
    static Map<String, String> canonical(Map<String, String> environment, Consumer<String> warnings) {
        // Windows environment names are case-insensitive, and System.getenv() looks them up that way.
        Map<String, String> result = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new HashMap<>();
        result.putAll(environment);
        LEGACY_ALIASES.forEach((legacy, name) -> {
            String old = result.remove(legacy);
            if (old == null || old.isBlank()) return;
            String current = result.get(name);
            if (current == null || current.isBlank()) {
                result.put(name, old);
                warnings.accept("[argos-config] " + legacy + " is deprecated; rename it to " + name + ".");
            } else if (current.strip().equals(old.strip())) {
                warnings.accept("[argos-config] " + legacy + " is deprecated and duplicates " + name
                        + "; remove " + legacy + ".");
            } else {
                warnings.accept("[argos-config] " + legacy + " and " + name + " are both set to different values; "
                        + name + " wins. Remove the deprecated " + legacy + ".");
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> legacyAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String name : NAMES) aliases.put("ARGOS_" + name, name);
        // These were ARGOS_PROFILE_DIR, ARGOS_HEADLESS, ARGOS_RESEARCH and ARGOS_SEARCH_HISTORY.
        rename(aliases, "BROWSER_PROFILE_DIR", "ARGOS_PROFILE_DIR");
        rename(aliases, "BROWSER_HEADLESS", "ARGOS_HEADLESS");
        rename(aliases, "RESEARCH_ENABLED", "ARGOS_RESEARCH");
        rename(aliases, "SEARCH_HISTORY_ENABLED", "ARGOS_SEARCH_HISTORY");
        return Collections.unmodifiableMap(aliases);
    }

    private static void rename(Map<String, String> aliases, String name, String legacy) {
        aliases.remove("ARGOS_" + name);
        aliases.put(legacy, name);
    }
}
