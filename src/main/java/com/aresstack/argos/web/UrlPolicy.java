package com.aresstack.argos.web;

import com.aresstack.argos.network.HostResolver;
import com.aresstack.argos.network.NetworkPolicy;
import com.aresstack.argos.network.NetworkSettings;

import java.net.URI;
import java.util.Locale;

/**
 * Early, informative URL check for the tools that navigate (web_*, downloads, batch, research).
 *
 * <p>It owns no security rules of its own: the target decision is delegated to the same
 * {@link NetworkPolicy} that Argos' local SOCKS5 proxy enforces for every browser connection
 * (analysis section 5.4, PR #11). The proxy is the boundary; this class only turns a refused
 * target into a clear {@code BLOCKED} error before the browser starts loading, and lets callers
 * keep refused redirect targets out of tool results. It resolves like the proxy does
 * ({@link NetworkPolicy#checkUrl}), so a host the proxy would refuse is refused here as well.
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
    private final HostResolver resolver;

    public UrlPolicy(NetworkPolicy network) {
        this(network, HostResolver.SYSTEM);
    }

    public UrlPolicy(NetworkPolicy network, HostResolver resolver) {
        this.network = network;
        this.resolver = resolver;
    }

    /** The informative check matching the configured network boundary. */
    public static UrlPolicy from(NetworkSettings settings) {
        return settings.strictMode() ? new UrlPolicy(settings.policy()) : off();
    }

    /** Like {@link #from(NetworkSettings)} with another host resolution (e.g. the evaluation's fixture domains). */
    public static UrlPolicy from(NetworkSettings settings, HostResolver resolver) {
        return settings.strictMode() ? new UrlPolicy(settings.policy(), resolver) : off();
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
        if (network != null) {
            NetworkPolicy.Decision decision = network.checkUrl(url, resolver);
            if (!decision.allowed()) {
                throw new BlockedUrlException("Blocked by the network policy: " + redacted(url)
                        + " (" + decision.reason() + ")");
            }
            return URI.create(url.trim()); // checkUrl validated scheme and host
        }
        return shape(url);
    }

    private static URI shape(String url) {
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
        return uri;
    }

    private static String redacted(String url) {
        URI uri = URI.create(url.trim());
        return uri.getScheme() + "://" + (uri.getHost() == null ? uri.getRawAuthority() : uri.getHost())
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
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
}
