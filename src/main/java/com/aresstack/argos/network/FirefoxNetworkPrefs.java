package com.aresstack.argos.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Firefox prefs that route every browser connection through Argos' SOCKS5 proxy. Checked against
 * Firefox {@code modules/libpref/init/StaticPrefList.yaml} / {@code all.js} and Camoufox 158
 * {@code settings/camoufox.cfg} (2026-10-25). Passed as user prefs at every launch, so the persistent
 * profile cannot override them.
 *
 * <p>Deliberately not set, because Camoufox keeps them at stock values to stay indistinguishable and
 * pages can observe a change through Resource Timing: {@code network.dns.disablePrefetch},
 * {@code network.prefetch-next}, {@code network.http.speculative-parallel-limit}. They need no change:
 * speculative connections use the proxy like any other, and Firefox skips DNS prefetch behind a proxy
 * ({@code network.dns.prefetch_via_proxy=false}). {@code network.predictor.enabled} no longer exists.
 */
public final class FirefoxNetworkPrefs {
    private FirefoxNetworkPrefs() {
    }

    public static Map<String, Object> forLocalSocks(int port) {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("network.proxy.type", 1);                          // manual configuration
        prefs.put("network.proxy.socks", "127.0.0.1");
        prefs.put("network.proxy.socks_port", port);
        prefs.put("network.proxy.socks_version", 5);
        prefs.put("network.proxy.socks5_remote_dns", true);          // SOCKS5: host name goes to the proxy
        prefs.put("network.proxy.socks_remote_dns", true);           // SOCKS4 variant, Camoufox sets it too
        prefs.put("network.proxy.http", "");                         // no HTTP/SSL proxy may take precedence
        prefs.put("network.proxy.http_port", 0);
        prefs.put("network.proxy.ssl", "");
        prefs.put("network.proxy.ssl_port", 0);
        prefs.put("network.proxy.share_proxy_settings", false);
        prefs.put("network.proxy.no_proxies_on", "");
        prefs.put("network.proxy.allow_hijacking_localhost", true);  // otherwise localhost/127.0.0.1 bypass
        prefs.put("network.proxy.failover_direct", false);           // no direct retry for system requests
        prefs.put("network.proxy.allow_bypass", false);              // no per-channel proxy bypass flag
        prefs.put("network.proxy.retry_failed_proxies", true);       // after refusals: retry proxy, never direct
        prefs.put("network.trr.mode", 5);                            // DoH off by choice, no resolver bypass
        prefs.put("network.dns.prefetch_via_proxy", false);
        prefs.put("network.http.altsvc.proxy_checks", true);         // no Alt-Svc/HTTP3 (UDP) around the proxy
        // Camoufox default: no ICE candidates that bypass a configured proxy (no UDP STUN leaves the box).
        prefs.put("media.peerconnection.ice.proxy_only_if_behind_proxy", true);
        return prefs;
    }

    /** Pref prefixes the boundary owns; launch.json must not configure them. */
    public static List<String> ownedPrefixes() {
        return List.of("network.proxy.", "network.trr.");
    }
}
