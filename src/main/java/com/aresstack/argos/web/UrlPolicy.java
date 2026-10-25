package com.aresstack.argos.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Navigation gate for the web_* tools, following the protection model of
 * docs/argos-optimization-analysis.md (PR #1): checked before navigating and again on
 * the final URL; a blocked final page is left for about:blank and never returned.
 *
 * <p>This is not an SSRF guarantee. The browser itself sends redirect hops and
 * subresource requests, and resolves DNS on its own, so an internal redirect target
 * has already been contacted when the final check fails, and DNS rebinding between
 * this check and the browser's lookup cannot be ruled out. Enforcement belongs to the
 * connection boundary (local SOCKS5 proxy, analysis section 5.4); this class gives early,
 * understandable errors and keeps blocked pages out of tool results.
 *
 * <p>Intranet rules ({@code ARGOS_INTRANET_ALLOW}, comma separated) admit private targets
 * explicitly: {@code wiki.firma.local} (exact host), {@code *.firma.local} (sub-domains),
 * {@code 10.20.0.0/16} or {@code 192.168.1.5} (resolved addresses). Everything else that
 * resolves to loopback, private, link-local, unique-local or CGNAT space is blocked unless
 * {@code ARGOS_ALLOW_PRIVATE_NETWORKS=true} turns the check off entirely.
 */
public final class UrlPolicy {
    /** A syntactically valid URL whose target the policy does not admit. */
    public static final class BlockedUrlException extends IllegalArgumentException {
        public BlockedUrlException(String message) {
            super(message);
        }
    }

    private final boolean allowPrivateNetworks;
    private final List<String> hostRules = new ArrayList<>();
    private final List<byte[][]> networkRules = new ArrayList<>();

    public UrlPolicy(boolean allowPrivateNetworks) {
        this(allowPrivateNetworks, List.of());
    }

    public UrlPolicy(boolean allowPrivateNetworks, List<String> intranetRules) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        for (String rule : intranetRules) {
            String entry = rule.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            if (entry.matches("[0-9a-f:.]+(/\\d{1,3})?") && (entry.contains(":") || entry.matches("[0-9./]+"))) {
                networkRules.add(network(entry));
            } else {
                hostRules.add(entry);
            }
        }
    }

    /** {@code ARGOS_ALLOW_PRIVATE_NETWORKS} and {@code ARGOS_INTRANET_ALLOW} from the environment. */
    public static UrlPolicy fromEnvironment() {
        String rules = System.getenv("ARGOS_INTRANET_ALLOW");
        return new UrlPolicy(Boolean.parseBoolean(System.getenv("ARGOS_ALLOW_PRIVATE_NETWORKS")),
                rules == null || rules.isBlank() ? List.of() : List.of(rules.split(",")));
    }

    public URI check(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Empty URL.");
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http(s) URLs can be opened, e.g. https://example.org/");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL has no host: " + url);
        }
        if (!allowPrivateNetworks) requirePublicOrAdmittedHost(uri.getHost());
        return uri;
    }

    /** Whether {@link #check} accepts {@code url}; for checks on redirect hops and final URLs. */
    public boolean permits(String url) {
        try {
            check(url);
            return true;
        } catch (IllegalArgumentException refused) {
            return false;
        }
    }

    private void requirePublicOrAdmittedHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (admittedByHostRule(host)) return;
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unknown) {
            throw new IllegalArgumentException("Cannot resolve host: " + host);
        }
        // Every resolved address counts: one private record is enough to reach an internal service.
        for (InetAddress address : addresses) {
            if (isPrivate(address) && !admittedByNetworkRule(address)) {
                throw new BlockedUrlException("Blocked private or loopback address: " + host
                        + " (allow it explicitly with ARGOS_INTRANET_ALLOW)");
            }
        }
    }

    private boolean admittedByHostRule(String host) {
        for (String rule : hostRules) {
            if (rule.startsWith("*.") ? host.endsWith(rule.substring(1)) : host.equals(rule)) return true;
        }
        return false;
    }

    private boolean admittedByNetworkRule(InetAddress address) {
        byte[] raw = address.getAddress();
        for (byte[][] rule : networkRules) {
            byte[] network = rule[0];
            int prefix = rule[1][0] & 0xff;
            if (network.length != raw.length) continue;
            boolean matches = true;
            for (int bit = 0; bit < prefix && matches; bit++) {
                int mask = 0x80 >> (bit % 8);
                matches = (network[bit / 8] & mask) == (raw[bit / 8] & mask);
            }
            if (matches) return true;
        }
        return false;
    }

    private static byte[][] network(String rule) {
        int slash = rule.indexOf('/');
        String address = slash < 0 ? rule : rule.substring(0, slash);
        try {
            // Literal addresses only: getByName does not query DNS for them.
            byte[] raw = InetAddress.getByName(address).getAddress();
            int prefix = slash < 0 ? raw.length * 8 : Integer.parseInt(rule.substring(slash + 1));
            if (prefix > raw.length * 8) throw new IllegalArgumentException("Invalid intranet rule: " + rule);
            return new byte[][]{raw, {(byte) prefix}};
        } catch (UnknownHostException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid intranet rule: " + rule);
        }
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 16) return (raw[0] & 0xfe) == 0xfc; // IPv6 unique-local fc00::/7
        return (raw[0] & 0xff) == 100 && (raw[1] & 0xc0) == 64; // carrier-grade NAT 100.64.0.0/10
    }
}
