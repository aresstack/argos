package com.aresstack.argos.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Navigation gate for the web_* tools, following the protection model of
 * docs/argos-optimization-analysis.md (PR #1): checked before navigating and again on
 * the final URL; a blocked final page is left for about:blank and never returned.
 *
 * <p>This is not an SSRF guarantee. The browser itself sends redirect hops and
 * subresource requests, and resolves DNS on its own, so an internal redirect target
 * has already been contacted when the final check fails, and DNS rebinding between
 * this check and the browser's lookup cannot be ruled out. Preventing that needs
 * request interception or a browser-level network block, which the analysis defers
 * (route interception is measurable and depends on the event pump). Host allow/block
 * rules for intranets belong to the shared URL policy of work package A.
 */
public final class UrlPolicy {
    private final boolean allowPrivateNetworks;

    public UrlPolicy(boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
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
        if (!allowPrivateNetworks) requirePublicHost(uri.getHost());
        return uri;
    }

    private static void requirePublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unknown) {
            throw new IllegalArgumentException("Cannot resolve host: " + host);
        }
        // Every resolved address counts: one private record is enough to reach an internal service.
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new IllegalArgumentException("Blocked private or loopback address: " + host);
            }
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
