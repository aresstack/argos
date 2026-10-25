package com.aresstack.argos.fetch;

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
