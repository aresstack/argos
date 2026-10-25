package com.aresstack.argos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured extraction from data the browser already handed over: an aria snapshot (computed in Playwright's
 * isolated world), the {@code <head>} markup and JSON-LD script texts. Pure functions, no browser access.
 */
final class PageExtractor {
    static final List<String> KINDS = List.of("overview", "headings", "links", "tables", "lists", "metadata", "jsonld");
    static final int MAX_LINKS = 200;
    static final int MAX_TABLES = 10;
    static final int MAX_ROWS = 50;
    static final int MAX_LISTS = 20;
    static final int MAX_ITEMS = 50;
    static final int MAX_CELL = 300;
    private static final Set<String> CELL_ROLES = Set.of("cell", "gridcell", "columnheader", "rowheader");
    private static final Pattern TAG = Pattern.compile("<(meta|link)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final ObjectMapper JSON = new ObjectMapper();

    private PageExtractor() {
    }

    static List<Map<String, Object>> headings(AriaTree tree, int limit) {
        List<Map<String, Object>> headings = new ArrayList<>();
        for (AriaTree.Node node : tree.findAll(n -> n.role().equals("heading"))) {
            if (headings.size() >= limit) break;
            String level = node.attribute("level");
            headings.add(Map.of("level", level.matches("\\d") ? Integer.parseInt(level) : 0,
                    "text", clip(node.label(), MAX_CELL)));
        }
        return headings;
    }

    /** Absolute http(s) links, de-duplicated, in document order. */
    static List<Map<String, Object>> links(AriaTree tree, String baseUrl, int limit) {
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AriaTree.Node node : tree.findAll(n -> n.role().equals("link"))) {
            if (links.size() >= limit) break;
            String url = absolute(baseUrl, node.property("url"));
            if (url == null || !seen.add(url)) continue;
            links.add(Map.of("text", clip(node.label(), MAX_CELL), "url", url));
        }
        return links;
    }

    static List<Map<String, Object>> tables(AriaTree tree) {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (AriaTree.Node table : tree.findAll(n -> n.role().equals("table") || n.role().equals("grid"))) {
            if (tables.size() >= MAX_TABLES) break;
            List<String> headers = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            int totalRows = 0;
            for (AriaTree.Node row : rowsOf(table)) {
                List<AriaTree.Node> cells = row.children().stream().filter(c -> CELL_ROLES.contains(c.role())).toList();
                if (cells.isEmpty()) continue;
                List<String> values = cells.stream().map(c -> clip(c.label(), MAX_CELL)).toList();
                if (headers.isEmpty() && rows.isEmpty() && cells.stream().allMatch(c -> c.role().equals("columnheader"))) {
                    headers.addAll(values);
                    continue;
                }
                totalRows++;
                if (rows.size() < MAX_ROWS) rows.add(values);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            if (!table.name().isBlank()) result.put("caption", clip(table.name(), MAX_CELL));
            result.put("headers", headers);
            result.put("rows", rows);
            if (totalRows > rows.size()) result.put("omittedRows", totalRows - rows.size());
            tables.add(result);
        }
        return tables;
    }

    /** Rows of a table, without descending into nested tables. */
    private static List<AriaTree.Node> rowsOf(AriaTree.Node table) {
        List<AriaTree.Node> rows = new ArrayList<>();
        collectRows(table, rows);
        return rows;
    }

    private static void collectRows(AriaTree.Node node, List<AriaTree.Node> rows) {
        for (AriaTree.Node child : node.children()) {
            if (child.role().equals("row")) rows.add(child);
            else if (!child.role().equals("table") && !child.role().equals("grid")) collectRows(child, rows);
        }
    }

    static List<Map<String, Object>> lists(AriaTree tree) {
        List<Map<String, Object>> lists = new ArrayList<>();
        for (AriaTree.Node list : tree.findAll(n -> n.role().equals("list"))) {
            if (lists.size() >= MAX_LISTS) break;
            List<AriaTree.Node> items = list.children().stream().filter(c -> c.role().equals("listitem")).toList();
            if (items.isEmpty()) continue;
            List<String> texts = items.stream().limit(MAX_ITEMS).map(i -> clip(i.label(), MAX_CELL)).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            if (!list.name().isBlank()) result.put("name", list.name());
            result.put("items", texts);
            if (items.size() > texts.size()) result.put("omittedItems", items.size() - texts.size());
            lists.add(result);
        }
        return lists;
    }

    /**
     * Description, canonical URL, Open Graph, Twitter, Dublin Core and citation metadata from the head markup.
     * {@code citation_*} tags matter for scholarly pages (Google Scholar conventions).
     */
    static Map<String, Object> metadata(String headHtml, String baseUrl) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (headHtml == null) return metadata;
        Matcher tag = TAG.matcher(headHtml);
        while (tag.find() && metadata.size() < 80) {
            Map<String, String> attributes = attributes(tag.group(2));
            if (tag.group(1).equalsIgnoreCase("link")) {
                String rel = attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
                if ((rel.equals("canonical") || rel.equals("alternate") && attributes.containsKey("hreflang"))
                        && attributes.containsKey("href")) {
                    String key = rel.equals("canonical") ? "canonical" : "alternate:" + attributes.get("hreflang");
                    String href = absolute(baseUrl, attributes.get("href"));
                    if (href != null) metadata.putIfAbsent(key, href);
                }
                continue;
            }
            String key = attributes.getOrDefault("name", attributes.getOrDefault("property", attributes.get("itemprop")));
            String content = attributes.get("content");
            if (key == null || content == null || content.isBlank()) continue;
            key = key.toLowerCase(Locale.ROOT);
            if (!interesting(key)) continue;
            String value = clip(content, 1_000);
            Object previous = metadata.get(key);
            // Repeated tags such as citation_author become lists.
            if (previous instanceof String text) metadata.put(key, new ArrayList<>(List.of(text, value)));
            else if (previous instanceof List<?> values) castList(values).add(value);
            else metadata.put(key, value);
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(List<?> values) {
        return (List<String>) values;
    }

    private static boolean interesting(String key) {
        return key.equals("description") || key.equals("keywords") || key.equals("author")
                || key.equals("robots") || key.startsWith("og:") || key.startsWith("article:")
                || key.startsWith("twitter:") || key.startsWith("dc.") || key.startsWith("dcterms.")
                || key.startsWith("citation_") || key.startsWith("prism.");
    }

    /** Parsed JSON-LD blocks; unparsable blocks are reported, not thrown. */
    static List<Object> jsonLd(List<String> scripts, int maxCharacters) {
        List<Object> blocks = new ArrayList<>();
        int used = 0;
        for (String script : scripts) {
            if (used >= maxCharacters) break;
            String source = script.strip();
            used += source.length();
            try {
                JsonNode node = JSON.readTree(source);
                blocks.add(source.length() > maxCharacters ? Map.of("truncated", clip(source, maxCharacters)) : node);
            } catch (Exception invalid) {
                blocks.add(Map.of("invalid", clip(source, 500)));
            }
        }
        return blocks;
    }

    /** {@code @type} values of JSON-LD blocks, including those inside {@code @graph}. */
    static List<String> jsonLdTypes(List<Object> blocks) {
        Set<String> types = new LinkedHashSet<>();
        for (Object block : blocks) {
            if (block instanceof JsonNode node) collectTypes(node, types, 0);
        }
        return List.copyOf(types);
    }

    private static void collectTypes(JsonNode node, Set<String> types, int depth) {
        if (depth > 3 || types.size() > 20) return;
        if (node.isArray()) node.forEach(child -> collectTypes(child, types, depth + 1));
        if (!node.isObject()) return;
        JsonNode type = node.get("@type");
        if (type != null && type.isTextual()) types.add(type.asText());
        if (type != null && type.isArray()) type.forEach(t -> types.add(t.asText()));
        JsonNode graph = node.get("@graph");
        if (graph != null) collectTypes(graph, types, depth + 1);
    }

    private static Map<String, String> attributes(String markup) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher attribute = ATTRIBUTE.matcher(markup);
        while (attribute.find()) {
            String value = attribute.group(2) != null ? attribute.group(2)
                    : attribute.group(3) != null ? attribute.group(3) : attribute.group(4);
            attributes.putIfAbsent(attribute.group(1).toLowerCase(Locale.ROOT), decodeEntities(value));
        }
        return attributes;
    }

    static String decodeEntities(String value) {
        return value.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    /** Absolute http(s) URL, or {@code null} for script, mail and fragment-only links. */
    static String absolute(String baseUrl, String href) {
        if (href == null || href.isBlank()) return null;
        try {
            URI resolved = baseUrl == null || baseUrl.isBlank() ? URI.create(href.strip())
                    : URI.create(baseUrl).resolve(href.strip().replace(" ", "%20"));
            String scheme = resolved.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            return resolved.toString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static String clip(String text, int max) {
        String value = text == null ? "" : text.strip();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
