package com.aresstack.argos.fetch;

import com.aresstack.argos.web.UrlPolicy;

import java.net.URI;
import java.util.Locale;

/** Absolute http(s) addresses as Argos accepts them for browser navigation. */
public final class WebAddress {
    private WebAddress() { }

    /** Resolves {@code target} against {@code base} (may be blank) and requires an absolute http(s) URL. */
    public static String requireHttp(String target, String base) {
        if (target == null || target.isBlank()) {
            throw new FetchFailure(FetchFailure.Kind.INVALID_REQUEST, "Missing URL");
        }
        URI uri;
        try {
            uri = URI.create(target.trim());
            if (!uri.isAbsolute() && base != null && !base.isBlank()) uri = URI.create(base).resolve(uri);
        } catch (IllegalArgumentException error) {
            throw new FetchFailure(FetchFailure.Kind.INVALID_REQUEST, "Not a valid URL: " + redacted(target));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new FetchFailure(FetchFailure.Kind.INVALID_REQUEST,
                    "Requires an absolute http(s) URL: " + redacted(target));
        }
        return uri.toString();
    }

    /**
     * Applies the URL policy before anything is loaded, with the failure kind an agent can act on:
     * {@code BLOCKED} for refused targets, {@code INVALID_REQUEST} for unusable addresses.
     */
    public static void admit(UrlPolicy policy, String url) {
        try {
            policy.check(url);
        } catch (UrlPolicy.BlockedUrlException blocked) {
            throw new FetchFailure(FetchFailure.Kind.BLOCKED, blocked.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new FetchFailure(FetchFailure.Kind.INVALID_REQUEST, invalid.getMessage());
        }
    }

    /** The address without fragment, used to recognise duplicates. */
    public static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    /** The address without user info (credentials), for metadata shown to the agent. */
    public static String withoutUserInfo(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getRawUserInfo() == null) return uri.toString();
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getRawPath() == null ? null
                    : uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (Exception invalid) {
            return redacted(url);
        }
    }

    /** Scheme, host, port and path only: no user info, query or fragment, for messages and metadata. */
    public static String redacted(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) return uri.getScheme() == null ? "(invalid)" : uri.getScheme() + ":";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
                    .toString();
        } catch (Exception invalid) {
            return "(invalid)";
        }
    }
}
