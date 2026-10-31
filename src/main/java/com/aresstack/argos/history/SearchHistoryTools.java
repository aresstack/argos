package com.aresstack.argos.history;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools of the Search History: {@code search_history} (full-text search over this chat's history),
 * {@code search_history_export} (txt, jsonl or zip), {@code search_history_capture_settings} (this chat's optional
 * resource categories) and {@code browser_archive_html} (explicit single-file HTML of the shown page, the only path
 * that runs the HTML collector). All act on the calling chat only; the export's location is
 * chosen by Argos, never by an argument. An export is handed to the chat as a download link on this server's own
 * origin ({@link SearchHistoryDownloads}), as text the model can pass on and as an MCP {@code resource_link}; the
 * local file path is never returned.
 */
public final class SearchHistoryTools {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final SearchHistory history;
    private final Archiver archiver;
    private final SearchHistoryDownloads downloads;
    /** Non-null when this server keeps no history: both tools stay listed and answer with this message. */
    private final String disabledReason;

    /** browser_archive_html's browser side ({@link SearchHistoryCapture#archiveHtml}). */
    @FunctionalInterface
    public interface Archiver {
        SearchHistoryCapture.HtmlArchiveResult archive(String tabId) throws Exception;
    }

    public SearchHistoryTools(SearchHistory history, Archiver archiver) {
        this(history, archiver, SearchHistoryDownloads.shared());
    }

    public SearchHistoryTools(SearchHistory history, Archiver archiver, SearchHistoryDownloads downloads) {
        this.history = history;
        this.archiver = archiver;
        this.downloads = downloads;
        this.disabledReason = null;
    }

    private SearchHistoryTools(String disabledReason) {
        this.history = null;
        this.archiver = null;
        this.downloads = null;
        this.disabledReason = disabledReason;
    }

    /**
     * The same tools for a server without Search History (PROFILE_ISOLATION=single or
     * SEARCH_HISTORY_ENABLED=false), so clients such as ChatGPT always see a stable tool list; every call answers with
     * {@code reason} as an error and touches no data.
     */
    public static SearchHistoryTools disabled(String reason) {
        return new SearchHistoryTools(reason);
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("search_history", "Search everything this chat searched and read before (queries, results, "
                                + "page texts and captured network responses such as JSON/XML data the pages "
                                + "loaded). Returns matching PAGE, SEARCH and RESOURCE hits, best match first. "
                                + "Example: {\"query\": \"camoufox license\"}",
                        Map.of("query", Map.of("type", "string", "description", "Words to find"),
                                "limit", Map.of("type", "integer", "description", "Maximum hits (1-50, default 10)")),
                        List.of("query"), this::search),
                tool("search_history_export", "Export this chat's search history as a file the user can download: "
                                + "txt (visited URLs, one per line), jsonl (all searches, page visits and captured "
                                + "responses with text) or zip (one HTML file per page plus index.html and the "
                                + "captured responses under resources/ with manifest.json). Never opens or "
                                + "re-reads a page. Returns a "
                                + "download link valid for a limited time; give it to the user as a Markdown link. "
                                + "Example: {\"format\": \"zip\"}",
                        Map.of("format", Map.of("type", "string", "description", "txt | jsonl | zip"),
                                "unique", Map.of("type", "boolean", "description",
                                        "txt only: each URL once, in order of its first visit"),
                                "include_results", Map.of("type", "boolean", "description",
                                        "txt only: also list the URLs of search results that were not opened")),
                        List.of("format"), this::export),
                tool("search_history_capture_settings", "Show or change which optional resource types this chat's "
                                + "search history stores from now on, in addition to the always stored page "
                                + "content (HTML, JSON, XML, text, Markdown, CSV): images, fonts, audio, video, "
                                + "scripts, styles, pdf, other. Affects only this chat and only responses that "
                                + "arrive later; nothing is loaded again. Without arguments it lists the effective "
                                + "values. Example: {\"set\": {\"images\": true}} or {\"reset\": true}",
                        Map.of("set", Map.of("type", "object", "description",
                                        "Categories to switch, e.g. {\"images\": true, \"pdf\": false}",
                                        "additionalProperties", Map.of("type", "boolean")),
                                "reset", Map.of("type", "boolean", "description",
                                        "Drop this chat's changes; the server's settings apply again")),
                        List.of(), this::captureSettings),
                tool("browser_archive_html", "Save the page the tab shows right now as ONE self-contained HTML file "
                                + "the user can download (rendered state, images and styles embedded). Call it ONLY "
                                + "when the user explicitly asks to archive or save the page as HTML; it never "
                                + "navigates (open the page first). It runs a collector script in the page, which "
                                + "may request the page's images and stylesheets again; those requests are visible "
                                + "to the site. Returns a download link. Example: {} or {\"tab\": \"t2\"}",
                        Map.of("tab", Map.of("type", "string", "description",
                                "Optional tab id from browser_tabs; default the current tab")),
                        List.of(), this::archiveHtml));
    }

    private String search(Map<String, Object> arguments) throws Exception {
        if (disabledReason != null) throw new IllegalStateException(disabledReason);
        Object query = arguments.get("query");
        if (!(query instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Empty query.");
        int limit = arguments.get("limit") instanceof Number n ? n.intValue()
                : arguments.get("limit") instanceof String s && s.strip().matches("\\d{1,3}")
                ? Integer.parseInt(s.strip()) : 10;
        List<SearchHistory.Found> found = history.search(text, limit);
        if (found.isEmpty()) return "No matches in this chat's search history for \"" + abbreviate(text) + "\".";
        StringBuilder out = new StringBuilder();
        int number = 1;
        for (SearchHistory.Found hit : found) {
            out.append(number++).append(". ");
            if ("page".equals(hit.type())) {
                out.append("PAGE ").append(hit.title().isBlank() ? "(no title)" : hit.title())
                        .append(" — ").append(hit.url()).append(" (visited ").append(hit.at()).append(")\n");
                if (hit.snippet() != null && !hit.snippet().isBlank()) out.append("   ").append(hit.snippet()).append('\n');
            } else if ("resource".equals(hit.type())) {
                out.append("RESOURCE ").append(hit.mimeType() == null || hit.mimeType().isBlank() ? "unknown type"
                        : hit.mimeType()).append(" — ").append(hit.url()).append(" (captured ").append(hit.at())
                        .append(")\n");
                if (hit.snippet() != null && !hit.snippet().isBlank()) out.append("   ").append(hit.snippet()).append('\n');
            } else {
                out.append("SEARCH \"").append(hit.title()).append("\" (").append(hit.at()).append(")\n");
            }
        }
        return out.toString().stripTrailing();
    }

    private CallToolResult export(Map<String, Object> arguments) throws Exception {
        if (disabledReason != null) throw new IllegalStateException(disabledReason);
        Object format = arguments.get("format");
        SearchHistoryExporter.Format chosen = SearchHistoryExporter.format(format instanceof String s ? s : null);
        SearchHistoryExporter.Export export = new SearchHistoryExporter(history.repository())
                .export(chosen, flag(arguments, "unique"), flag(arguments, "include_results"));
        String what = switch (chosen) {
            case TXT -> " URLs";
            case JSONL -> " records";
            case ZIP -> " archived pages";
        };
        SearchHistoryDownloads.Link link = downloads.register(history.repository().exportsDirectory(), export.file());
        if (link.url() == null) {
            throw new IllegalStateException("Exported " + export.records() + what + " (" + link.fileName()
                    + "), but this Argos server knows no address to offer it under. Start it with PUBLIC_ORIGIN.");
        }
        long minutes = downloads.retention().toMinutes();
        String text = "Exported " + export.records() + what + " as " + link.fileName() + " (" + link.size()
                + " bytes). Download (valid " + minutes + " minutes, until " + link.expires() + "): ["
                + link.fileName() + "](" + link.url() + ")";
        return CallToolResult.builder()
                .addContent(new TextContent(text))
                .addContent(ResourceLink.builder().name(link.fileName()).title(link.fileName()).uri(link.url())
                        .mimeType(link.contentType()).size(link.size())
                        .description("Search history export, valid " + minutes + " minutes").build())
                .build();
    }

    private String captureSettings(Map<String, Object> arguments) throws Exception {
        if (disabledReason != null) throw new IllegalStateException(disabledReason);
        CaptureSettings settings = history.captureSettings();
        if (flag(arguments, "reset")) settings.reset();
        Object set = arguments.get("set");
        if (set != null) {
            if (!(set instanceof Map<?, ?> wanted)) throw new IllegalArgumentException("set must be an object.");
            Map<ResourceCategory, Boolean> changes = new EnumMap<>(ResourceCategory.class);
            for (Map.Entry<?, ?> entry : wanted.entrySet()) {
                ResourceCategory category = CaptureSettings.category(String.valueOf(entry.getKey()));
                if (category == null) {
                    throw new IllegalArgumentException("Unknown category \"" + abbreviate(String.valueOf(entry.getKey()))
                            + "\"; use images, fonts, audio, video, scripts, styles, pdf or other.");
                }
                Object value = entry.getValue();
                if (value instanceof Boolean b) changes.put(category, b);
                else if (value instanceof String text && (text.strip().equalsIgnoreCase("true")
                        || text.strip().equalsIgnoreCase("false"))) changes.put(category, Boolean.parseBoolean(text.strip()));
                else throw new IllegalArgumentException(category.key() + " must be true or false.");
            }
            settings.set(changes);
        }
        StringBuilder out = new StringBuilder("Search history capture settings of this chat (apply to responses "
                + "arriving from now on; already loaded pages are not fetched again):\n- content (html, json, xml, "
                + "text, markdown, csv): on (always)\n");
        for (Map.Entry<ResourceCategory, CaptureSettings.Value> entry : settings.effective().entrySet()) {
            out.append("- ").append(entry.getKey().key()).append(": ").append(entry.getValue().enabled() ? "on" : "off")
                    .append(" (").append("chat".equals(entry.getValue().source()) ? "changed in this chat"
                            : "server setting " + entry.getKey().variable).append(")\n");
        }
        return out.toString().stripTrailing();
    }

    private CallToolResult archiveHtml(Map<String, Object> arguments) throws Exception {
        if (disabledReason != null) throw new IllegalStateException(disabledReason);
        Object tab = arguments.get("tab");
        SearchHistoryCapture.HtmlArchiveResult archived = archiver.archive(
                tab instanceof String text && !text.isBlank() ? text.strip() : null);
        Path file = writeArchive(history.repository().exportsDirectory(), archived.html());
        SearchHistoryDownloads.Link link = downloads.register(history.repository().exportsDirectory(), file);
        if (link.url() == null) {
            throw new IllegalStateException("Archived the page, but this Argos server knows no address to offer the "
                    + "file under. Start it with PUBLIC_ORIGIN.");
        }
        long minutes = downloads.retention().toMinutes();
        String title = archived.title() == null || archived.title().isBlank() ? archived.url() : archived.title();
        String text = "Archived \"" + abbreviate(title) + "\" (" + archived.url() + ") as one self-contained HTML file ("
                + link.size() + " bytes); later zip exports of the search history include it. Download (valid "
                + minutes + " minutes, until " + link.expires() + "): [" + link.fileName() + "](" + link.url() + ")";
        return CallToolResult.builder()
                .addContent(new TextContent(text))
                .addContent(ResourceLink.builder().name(link.fileName()).title(abbreviate(title)).uri(link.url())
                        .mimeType(link.contentType()).size(link.size())
                        .description("Single-file HTML archive, valid " + minutes + " minutes").build())
                .build();
    }

    /** {@code exports/page-archive-<time>.html}; the name is Argos-made, never from an argument. */
    static Path writeArchive(Path directory, String html) throws java.io.IOException {
        Files.createDirectories(directory);
        String stem = "page-archive-" + STAMP.format(Instant.now());
        Path file = directory.resolve(stem + ".html");
        for (int i = 1; Files.exists(file); i++) file = directory.resolve(stem + "-" + i + ".html");
        Path temporary = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(temporary, html, StandardCharsets.UTF_8);
        Files.move(temporary, file);
        return file;
    }

    private static boolean flag(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        return value instanceof Boolean b ? b : value instanceof String text && Boolean.parseBoolean(text.strip());
    }

    @FunctionalInterface
    private interface Handler {
        /** A String answer becomes one text content; a CallToolResult is returned as is. */
        Object apply(Map<String, Object> arguments) throws Exception;
    }

    private static SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
                                              List<String> required, Handler handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description)
                        .inputSchema(new JsonSchema("object", properties, required, false, Map.of(), Map.of()))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        Object answer = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                        return answer instanceof CallToolResult result ? result
                                : CallToolResult.builder().addTextContent((String) answer).build();
                    } catch (Exception error) {
                        String message = error.getMessage() == null ? error.getClass().getSimpleName()
                                : error.getMessage();
                        return CallToolResult.builder().isError(true).addTextContent(message).build();
                    }
                }).build();
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }
}
