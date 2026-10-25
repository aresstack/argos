package com.aresstack.argos.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from recorded network data before any of it leaves Argos.
 * Masking is name-based (headers, query and form parameters, JSON keys) plus
 * pattern-based for bearer tokens and JWTs that appear in free text.
 */
public final class SecretMasker {
    public static final String MASK = "[REDACTED]";

    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie");
    private static final List<String> SECRET_NAME_PARTS = List.of(
            "pass", "pwd", "secret", "token", "auth", "session", "cookie", "apikey", "api_key",
            "api-key", "signature", "credential", "jwt", "csrf", "xsrf", "private");
    private static final Set<String> SECRET_NAMES = Set.of(
            "key", "sig", "sid", "code", "otp", "pin", "ticket", "nonce");
    private static final Set<String> COOKIE_ATTRIBUTES = Set.of(
            "path", "domain", "expires", "max-age", "secure", "httponly", "samesite", "partitioned", "priority");

    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    // "token": "value" inside scripts, HTML-embedded JSON or JSON that failed to parse.
    private static final Pattern QUOTED_PAIR = Pattern.compile(
            "([\"']?)([A-Za-z0-9_.-]{2,64})\\1(\\s*[:=]\\s*)([\"'])([^\"'\\r\\n]{1,4096})\\4");
    // <input name="csrf_token" value="..."> in either attribute order.
    private static final Pattern INPUT_VALUE = Pattern.compile(
            "(?i)(name\\s*=\\s*[\"']([^\"']{1,64})[\"'][^>]{0,200}?value\\s*=\\s*)([\"'])([^\"']*)\\3");

    private final ObjectMapper json = new ObjectMapper();

    public static boolean isSecretName(String name) {
        if (name == null) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (SECRET_NAMES.contains(lower)) return true;
        for (String part : SECRET_NAME_PARTS) {
            if (lower.contains(part)) return true;
        }
        return false;
    }

    /** Scheme, host, port and path only — the historic, conservative list representation. */
    public String urlWithoutQuery(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) return schemeOnly(url);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (Exception unparseable) {
            return schemeOnly(url);
        }
    }

    /** Full URL with user info and fragment removed and secret query values masked. */
    public String url(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) return schemeOnly(url);
            String base = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
            String query = uri.getRawQuery();
            return query == null || query.isEmpty() ? base : base + "?" + formEncoded(query);
        } catch (Exception unparseable) {
            return schemeOnly(url);
        }
    }

    public Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> masked = new LinkedHashMap<>();
        if (headers == null) return masked;
        headers.forEach((name, value) -> masked.put(name, header(name, value)));
        return masked;
    }

    public String header(String name, String value) {
        if (value == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("cookie")) return cookiePairs(value, ";");
        if (lower.equals("set-cookie")) {
            // Playwright joins multiple Set-Cookie headers with newlines.
            StringBuilder out = new StringBuilder();
            for (String cookie : value.split("\n")) {
                if (!out.isEmpty()) out.append('\n');
                out.append(setCookie(cookie));
            }
            return out.toString();
        }
        if (SECRET_HEADERS.contains(lower) || isSecretName(lower)) return MASK;
        if (lower.equals("location") || lower.equals("referer") || lower.equals("content-location")) {
            return url(value);
        }
        return text(value);
    }

    /** Masks a captured request or response body according to its MIME type. */
    public String body(String body, String mimeType) {
        if (body == null || body.isEmpty()) return body;
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mime.contains("x-www-form-urlencoded")) return formEncoded(body);
        String trimmed = body.stripLeading();
        if (mime.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode tree = json.readTree(body);
                if (tree != null && tree.isContainerNode()) {
                    maskJson(tree);
                    return text(json.writeValueAsString(tree));
                }
            } catch (Exception notJson) {
                // Fall through to free-text masking.
            }
        }
        return text(body);
    }

    /** Pattern-based masking for any free text (headers, HTML, scripts, error messages). */
    public String text(String value) {
        if (value == null || value.isEmpty()) return value;
        String masked = JWT.matcher(value).replaceAll(MASK);
        masked = BEARER.matcher(masked).replaceAll("$1 " + MASK);
        masked = replaceSecretPairs(masked);
        return replaceSecretInputs(masked);
    }

    private void maskJson(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (isSecretName(name) && (child.isValueNode() && !child.isNull())) {
                    object.put(name, MASK);
                } else {
                    maskJson(child);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::maskJson);
        }
    }

    private String formEncoded(String query) {
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&", -1)) {
            if (!out.isEmpty()) out.append('&');
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (equals >= 0 && isSecretName(decode(name))) {
                out.append(name).append('=').append(MASK);
            } else {
                out.append(text(pair));
            }
        }
        return out.toString();
    }

    private static String cookiePairs(String value, String separator) {
        StringBuilder out = new StringBuilder();
        for (String pair : value.split(separator)) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;
            if (!out.isEmpty()) out.append(separator).append(' ');
            int equals = trimmed.indexOf('=');
            out.append(equals < 0 ? MASK : trimmed.substring(0, equals) + "=" + MASK);
        }
        return out.toString();
    }

    private static String setCookie(String cookie) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (!out.isEmpty()) out.append("; ");
            int equals = trimmed.indexOf('=');
            String name = equals < 0 ? trimmed : trimmed.substring(0, equals);
            if (first) {
                out.append(name).append('=').append(MASK);
                first = false;
            } else if (COOKIE_ATTRIBUTES.contains(name.toLowerCase(Locale.ROOT))) {
                out.append(trimmed);
            } else {
                out.append(name).append(equals < 0 ? "" : "=" + MASK);
            }
        }
        return out.toString();
    }

    private static String replaceSecretPairs(String value) {
        Matcher matcher = QUOTED_PAIR.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = isSecretName(matcher.group(2))
                    ? matcher.group(1) + matcher.group(2) + matcher.group(1) + matcher.group(3)
                      + matcher.group(4) + MASK + matcher.group(4)
                    : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceSecretInputs(String value) {
        Matcher matcher = INPUT_VALUE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = isSecretName(matcher.group(2))
                    ? matcher.group(1) + matcher.group(3) + MASK + matcher.group(3)
                    : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    private static String schemeOnly(String url) {
        int colon = url == null ? -1 : url.indexOf(':');
        return colon >= 0 ? url.substring(0, colon + 1) : "";
    }
}
