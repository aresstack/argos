package com.aresstack.argos.search;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Static unwrapping of search-engine redirect wrappers (ported from AskAI's SearchRedirectResolver).
 * The direct target is the navigation candidate; a wrapper whose target cannot be extracted safely is
 * UNRESOLVED and is never classified by the engine's own host.
 */
final class SearchRedirects {
    enum Status { NOT_A_REDIRECT, RESOLVED, UNRESOLVED }

    record Resolution(Status status, String targetUrl) {
    }

    private SearchRedirects() {
    }

    static Resolution resolve(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http")) return new Resolution(Status.NOT_A_REDIRECT, url == null ? "" : url);
        String host = DomainNames.hostOf(lower);
        String path = pathOf(lower);
        if (host.endsWith("bing.com") && path.startsWith("/ck/")) {
            String target = decodeBingTarget(queryParameter(url, "u"));
            return target == null ? unresolved() : new Resolution(Status.RESOLVED, target);
        }
        if (isGoogleHost(host) && (path.equals("/url") || path.startsWith("/url/"))) {
            String target = firstNonEmpty(queryParameter(url, "q"), queryParameter(url, "url"), queryParameter(url, "u"));
            return isHttp(target) ? new Resolution(Status.RESOLVED, target) : unresolved();
        }
        if (host.endsWith("duckduckgo.com") && path.startsWith("/l/")) {
            String target = queryParameter(url, "uddg");
            return isHttp(target) ? new Resolution(Status.RESOLVED, target) : unresolved();
        }
        return new Resolution(Status.NOT_A_REDIRECT, url);
    }

    private static boolean isGoogleHost(String host) {
        return host.equals("google.com") || host.startsWith("google.") || host.contains(".google.");
    }

    private static Resolution unresolved() {
        return new Resolution(Status.UNRESOLVED, "");
    }

    private static boolean isHttp(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    /** Bing encodes the target as "a1" + base64url; anything else is unresolved. */
    private static String decodeBingTarget(String encoded) {
        if (encoded == null || !encoded.startsWith("a1")) return null;
        try {
            String base64 = encoded.substring(2);
            int remainder = base64.length() % 4;
            if (remainder == 2) base64 += "==";
            else if (remainder == 3) base64 += "=";
            String target = new String(Base64.getUrlDecoder().decode(base64), StandardCharsets.UTF_8);
            return isHttp(target) ? target : null;
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    static String queryParameter(String url, String name) {
        int question = url.indexOf('?');
        if (question < 0) return null;
        String query = url.substring(question + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) query = query.substring(0, fragment);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException undecodable) {
                    return null; // never guessed
                }
            }
        }
        return null;
    }

    private static String pathOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return "";
        String rest = url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        if (slash < 0) return "/";
        String path = rest.substring(slash);
        int question = path.indexOf('?');
        return question < 0 ? path : path.substring(0, question);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }
}
