package com.aresstack.argos.web;

import com.aresstack.argos.network.NetworkPolicy;
import com.aresstack.argos.network.NetworkSettings;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Early, informative URL check for the tools that navigate (web_*, downloads, batch, research).
 *
 * <p>It owns no security rules of its own: the target decision is delegated to the same
 * {@link NetworkPolicy} that Argos' local SOCKS5 proxy enforces for every browser connection
 * (analysis section 5.4, PR #11). The proxy is the boundary; this class only turns a refused
 * target into a clear {@code BLOCKED} error before the browser starts loading, and lets callers
 * keep refused redirect targets out of tool results. A host that does not resolve locally is left
 * to the browser and the proxy, which decide on their own resolution.
 *
 * <p>With {@code ARGOS_NETWORK_POLICY=off} there is no boundary, and this check only validates
 * the URL shape.
 */
public final class UrlPolicy {
    /** A syntactically valid URL whose target the network policy does not admit. */
    public static final class BlockedUrlException extends IllegalArgumentException {
        public BlockedUrlException(String message) {
            super(message);
        }
    }

    /** Null when the network policy is off. */
    private final NetworkPolicy network;

    public UrlPolicy(NetworkPolicy network) {
        this.network = network;
    }

    /** The informative check matching the configured network boundary. */
    public static UrlPolicy from(NetworkSettings settings) {
        return settings.strictMode() ? new UrlPolicy(settings.policy()) : off();
    }

    /** Public targets only, the default of {@code ARGOS_NETWORK_POLICY=strict}. */
    public static UrlPolicy publicOnly() {
        return new UrlPolicy(NetworkPolicy.publicOnly());
    }

    /** Public targets plus the given {@code ARGOS_INTRANET_ALLOW} rules (comma separated). */
    public static UrlPolicy allowing(String intranetRules) {
        return new UrlPolicy(NetworkPolicy.parse(intranetRules));
    }

    /** URL shape only, for {@code ARGOS_NETWORK_POLICY=off} and tests against local fixtures. */
    public static UrlPolicy off() {
        return new UrlPolicy(null);
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
        if (network != null) requireAdmitted(uri.getHost());
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

    private void requireAdmitted(String rawHost) {
        String host = rawHost.startsWith("[") && rawHost.endsWith("]")
                ? rawHost.substring(1, rawHost.length() - 1) : rawHost;
        List<InetAddress> addresses;
        try {
            addresses = List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException unresolved) {
            return; // the proxy resolves and decides
        }
        NetworkPolicy.Decision decision = network.check(host, addresses);
        if (!decision.allowed()) {
            throw new BlockedUrlException("Blocked by the network policy: " + host + " (" + decision.reason() + ")");
        }
    }
}
