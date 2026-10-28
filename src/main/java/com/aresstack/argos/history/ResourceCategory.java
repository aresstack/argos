package com.aresstack.argos.history;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MIME classification of captured network responses. {@link #CONTENT} is the default research allowlist (HTML,
 * XHTML, JSON and {@code +json}, XML and {@code +xml}, plain text, Markdown, CSV); every other category is captured
 * only when switched on. Classification uses the normalized MIME type (parameters such as {@code charset} removed),
 * never a file extension, and checks the known optional categories BEFORE the allowlist, so CSS, JavaScript and SVG
 * never pass as text or XML. A missing or malformed Content-Type is {@link #OTHER}.
 */
public enum ResourceCategory {
    CONTENT(null),
    IMAGES("ARGOS_SEARCH_HISTORY_CAPTURE_IMAGES"),
    FONTS("ARGOS_SEARCH_HISTORY_CAPTURE_FONTS"),
    AUDIO("ARGOS_SEARCH_HISTORY_CAPTURE_AUDIO"),
    VIDEO("ARGOS_SEARCH_HISTORY_CAPTURE_VIDEO"),
    SCRIPTS("ARGOS_SEARCH_HISTORY_CAPTURE_SCRIPTS"),
    STYLES("ARGOS_SEARCH_HISTORY_CAPTURE_STYLES"),
    PDF("ARGOS_SEARCH_HISTORY_CAPTURE_PDF"),
    OTHER("ARGOS_SEARCH_HISTORY_CAPTURE_OTHER");

    private static final Pattern MIME = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");
    private static final Set<String> FONT_TYPES = Set.of("application/font-woff", "application/font-woff2",
            "application/x-font-woff", "application/x-font-ttf", "application/x-font-truetype",
            "application/x-font-opentype", "application/x-font-otf", "application/font-sfnt", "application/font-ttf",
            "application/font-otf", "application/vnd.ms-fontobject", "application/vnd.ms-opentype");
    private static final Set<String> SCRIPT_TYPES = Set.of("application/javascript", "application/ecmascript",
            "application/x-javascript", "application/x-ecmascript", "text/javascript", "text/ecmascript",
            "text/x-javascript", "text/x-ecmascript", "text/jscript", "text/livescript", "text/javascript1.0",
            "text/javascript1.1", "text/javascript1.2", "text/javascript1.3", "text/javascript1.4",
            "text/javascript1.5", "application/node", "text/babel", "text/jsx");
    private static final Set<String> CONTENT_TYPES = Set.of("text/html", "application/xhtml+xml",
            "application/json", "application/xml", "text/xml", "text/plain", "text/markdown", "text/x-markdown",
            "text/csv");

    /** The switch of an optional category; null for the always captured {@link #CONTENT}. */
    public final String variable;

    ResourceCategory(String variable) {
        this.variable = variable;
    }

    /** The settings key, e.g. {@code images}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code type/subtype} in lower case without parameters, or "" when missing or malformed. */
    public static String normalize(String contentType) {
        if (contentType == null) return "";
        int semicolon = contentType.indexOf(';');
        String mime = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip()
                .toLowerCase(Locale.ROOT);
        return MIME.matcher(mime).matches() ? mime : "";
    }

    public static ResourceCategory classify(String contentType) {
        String mime = normalize(contentType);
        if (mime.isEmpty()) return OTHER;
        // Known optional categories first: image/svg+xml, text/css and text/javascript must never count as content.
        if (mime.startsWith("image/")) return IMAGES;
        if (mime.startsWith("font/") || FONT_TYPES.contains(mime)) return FONTS;
        if (mime.startsWith("audio/")) return AUDIO;
        if (mime.startsWith("video/")) return VIDEO;
        if (SCRIPT_TYPES.contains(mime)) return SCRIPTS;
        if (mime.equals("text/css")) return STYLES;
        if (mime.equals("application/pdf")) return PDF;
        if (CONTENT_TYPES.contains(mime)) return CONTENT;
        if (mime.startsWith("application/") && (mime.endsWith("+json") || mime.endsWith("+xml"))) return CONTENT;
        return OTHER;
    }

    /** How a stored body of this MIME type is treated: decoded and searchable text, or bytes only. */
    public enum TextKind { HTML, TEXT, CODE, BINARY }

    public static TextKind textKind(String contentType) {
        String mime = normalize(contentType);
        return switch (classify(mime)) {
            case CONTENT -> mime.equals("text/html") || mime.equals("application/xhtml+xml") ? TextKind.HTML
                    : TextKind.TEXT;
            case SCRIPTS, STYLES -> TextKind.CODE;
            default -> TextKind.BINARY;
        };
    }
}
