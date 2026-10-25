package com.aresstack.argos.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Gate for every agent-driven navigation: only absolute http(s) URLs, and no
 * loopback/private/link-local targets unless explicitly allowed. Applied before
 * navigating and again on the final URL after redirects.
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
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException unknown) {
            throw new IllegalArgumentException("Cannot resolve host: " + host);
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            throw new IllegalArgumentException("Blocked private or loopback address: " + host);
        }
    }
}
