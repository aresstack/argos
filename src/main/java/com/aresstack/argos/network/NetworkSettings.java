package com.aresstack.argos.network;

import java.util.Locale;

/**
 * Network boundary configuration (analysis section 5.4).
 *
 * @param mode       {@code strict} (default): every browser connection goes through Argos' local SOCKS5
 *                   proxy and its {@link NetworkPolicy}; {@code off}: the browser connects directly
 * @param policy     admitted intranet targets
 * @param upstream   optional proxy to chain, or null for direct connections
 * @param upstreamDns {@code local}: Argos resolves, checks and hands the checked IP to the upstream (no
 *                   rebinding possible); {@code remote}: the upstream resolves again for geo-consistent
 *                   DNS, so the policy only sees Argos' own resolution of the host
 */
public record NetworkSettings(Mode mode, NetworkPolicy policy, UpstreamProxy upstream, UpstreamDns upstreamDns) {
    public enum Mode { STRICT, OFF }

    public enum UpstreamDns { LOCAL, REMOTE }

    public NetworkSettings {
        if (mode == null) mode = Mode.STRICT;
        if (policy == null) policy = NetworkPolicy.publicOnly();
        if (upstreamDns == null) upstreamDns = UpstreamDns.LOCAL;
    }

    public static NetworkSettings strict() {
        return new NetworkSettings(Mode.STRICT, NetworkPolicy.publicOnly(), null, UpstreamDns.LOCAL);
    }

    public static NetworkSettings parse(String mode, String intranetAllow, String upstream, String upstreamDns) {
        return new NetworkSettings(
                parseEnum(Mode.class, mode, "NETWORK_POLICY", "strict", "off"),
                NetworkPolicy.parse(intranetAllow),
                upstream == null || upstream.isBlank() ? null : UpstreamProxy.parse(upstream, null, null),
                parseEnum(UpstreamDns.class, upstreamDns, "UPSTREAM_DNS", "local", "remote"));
    }

    public boolean strictMode() {
        return mode == Mode.STRICT;
    }

    public NetworkSettings withUpstream(UpstreamProxy proxy) {
        return new NetworkSettings(mode, policy, proxy, upstreamDns);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String name, String... allowed) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (String option : allowed) {
            if (option.equals(normalized)) return Enum.valueOf(type, normalized.toUpperCase(Locale.ROOT));
        }
        throw new IllegalArgumentException(name + " must be one of " + String.join(", ", allowed) + ": " + value);
    }
}
