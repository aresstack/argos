package com.aresstack.argos.page;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Set;

/**
 * Kind of top-level document in the page. Decides how text is obtained, so a
 * snapshot never assumes that every HTTP document has an HTML {@code body}.
 */
public enum DocumentType {
    HTML, XML, FEED, JSON, TEXT, BINARY, UNKNOWN;

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "log");
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub",
            "zip", "gz", "tar", "7z", "png", "jpg", "jpeg", "gif", "webp", "svgz", "mp3", "mp4", "webm");

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True when the response body itself is the readable content (no rendered HTML DOM). */
    public boolean isSourceDocument() {
        return this == XML || this == FEED || this == JSON || this == TEXT;
    }

    /**
     * Classify by the main response's Content-Type header; the URL path is only a
     * fallback when the header is missing or generic ({@code application/octet-stream}).
     */
    public static DocumentType classify(String contentType, String url) {
        String mime = mimeType(contentType);
        DocumentType byHeader = byMimeType(mime);
        if (byHeader != UNKNOWN && !"application/octet-stream".equals(mime)) return byHeader;
        DocumentType byPath = byExtension(url);
        return byPath != UNKNOWN ? byPath : byHeader;
    }

    /** The lower-case media type without parameters, or an empty string. */
    public static String mimeType(String contentType) {
        if (contentType == null) return "";
        int semicolon = contentType.indexOf(';');
        String mime = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return mime.trim().toLowerCase(Locale.ROOT);
    }

    private static DocumentType byMimeType(String mime) {
        if (mime.isEmpty()) return UNKNOWN;
        if (mime.equals("text/html") || mime.equals("application/xhtml+xml")) return HTML;
        if (mime.equals("application/rss+xml") || mime.equals("application/atom+xml")
                || mime.equals("application/rdf+xml") || mime.equals("application/feed+json")) {
            return mime.endsWith("json") ? JSON : FEED;
        }
        if (mime.equals("application/json") || mime.endsWith("+json") || mime.equals("text/json")) return JSON;
        if (mime.equals("application/xml") || mime.equals("text/xml") || mime.endsWith("+xml")) return XML;
        if (mime.startsWith("text/")) return TEXT;
        if (mime.equals("application/javascript") || mime.equals("application/x-javascript")) return TEXT;
        return BINARY;
    }

    private static DocumentType byExtension(String url) {
        if (url == null) return UNKNOWN;
        String path = url;
        try {
            String rawPath = java.net.URI.create(url).getPath();
            if (rawPath != null) path = rawPath;
        } catch (IllegalArgumentException ignored) {
            // Keep the raw string; only its suffix matters.
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < path.lastIndexOf('/')) return UNKNOWN;
        String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html", "htm", "xhtml" -> HTML;
            case "rss", "atom" -> FEED;
            case "xml" -> XML;
            case "json" -> JSON;
            default -> TEXT_EXTENSIONS.contains(extension) ? TEXT
                    : BINARY_EXTENSIONS.contains(extension) ? BINARY : UNKNOWN;
        };
    }
}
