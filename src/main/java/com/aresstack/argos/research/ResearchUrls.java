package com.aresstack.argos.research;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** URL identity for de-duplication, host families and links that are documents rather than pages. */
final class ResearchUrls {
    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "gclid", "fbclid", "msclkid");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "csv", "zip", "epub");
    private static final Set<String> MULTI_LABEL_SUFFIXES = Set.of(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "co.nz", "co.jp", "com.br", "co.in", "github.io");

    private ResearchUrls() {
    }

    /**
     * AskAI's {@code canonicalish} plus what Argos meets more often: scheme and {@code www.} do not make
     * a different page, tracking parameters neither. Lower-cased only in scheme and host.
     */
    static String canonical(String url) {
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = withoutTracking(uri.getRawQuery());
            int port = uri.getPort();
            return host + (port < 0 ? "" : ":" + port) + path + (query.isEmpty() ? "" : "?" + query);
        } catch (IllegalArgumentException malformed) {
            return url.trim();
        }
    }

    static boolean isHttp(String url) {
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    static String host(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    /** The registrable domain: blocks and search-provider transit apply to the whole site family. */
    static String family(String urlOrHost) {
        String host = urlOrHost.contains("://") ? host(urlOrHost) : urlOrHost.toLowerCase(Locale.ROOT);
        if (host.matches("[0-9.]+") || host.contains(":")) return host;
        String[] labels = host.split("\\.");
        if (labels.length <= 2) return host;
        String lastTwo = labels[labels.length - 2] + "." + labels[labels.length - 1];
        if (MULTI_LABEL_SUFFIXES.contains(lastTwo)) return labels[labels.length - 3] + "." + lastTwo;
        return lastTwo;
    }

    /** Downloadable files are listed for the document tools, never navigated by the research. */
    static boolean isDocument(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            if (path == null) return false;
            int dot = path.lastIndexOf('.');
            return dot > path.lastIndexOf('/') && DOCUMENT_EXTENSIONS.contains(
                    path.substring(dot + 1).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String withoutTracking(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        StringBuilder kept = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (name.isEmpty() || TRACKING_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) continue;
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        return kept.toString();
    }
}
