package com.aresstack.argos.history;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/**
 * MCP tools of the Search History: {@code search_history} (full-text search over this chat's history) and
 * {@code search_history_export} (txt, jsonl or zip). Both act on the calling chat only; the export's location is
 * chosen by Argos, never by an argument. An export is handed to the chat as a download link on this server's own
 * origin ({@link SearchHistoryDownloads}), as text the model can pass on and as an MCP {@code resource_link}; the
 * local file path is never returned.
 */
public final class SearchHistoryTools {
    private final SearchHistory history;
    private final SearchHistoryDownloads downloads;
    /** Non-null when this server keeps no history: both tools stay listed and answer with this message. */
    private final String disabledReason;

    public SearchHistoryTools(SearchHistory history) {
        this(history, SearchHistoryDownloads.shared());
    }

    public SearchHistoryTools(SearchHistory history, SearchHistoryDownloads downloads) {
        this.history = history;
        this.downloads = downloads;
        this.disabledReason = null;
    }

    private SearchHistoryTools(String disabledReason) {
        this.history = null;
        this.downloads = null;
        this.disabledReason = disabledReason;
    }

    /**
     * The same two tools for a server without Search History (ARGOS_PROFILE_ISOLATION=single or
     * ARGOS_SEARCH_HISTORY=false), so clients such as ChatGPT always see a stable tool list; every call answers with
     * {@code reason} as an error and touches no data.
     */
    public static SearchHistoryTools disabled(String reason) {
        return new SearchHistoryTools(reason);
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("search_history", "Search everything this chat searched and read before (queries, results "
                                + "and page texts). Returns matching pages and searches, best match first. "
                                + "Example: {\"query\": \"camoufox license\"}",
                        Map.of("query", Map.of("type", "string", "description", "Words to find"),
                                "limit", Map.of("type", "integer", "description", "Maximum hits (1-50, default 10)")),
                        List.of("query"), this::search),
                tool("search_history_export", "Export this chat's search history as a file the user can download: "
                                + "txt (visited URLs, one per line), jsonl (all searches and page visits with text) "
                                + "or zip (one self-contained HTML file per page plus index.html). Returns a "
                                + "download link valid for a limited time; give it to the user as a Markdown link. "
                                + "Example: {\"format\": \"zip\"}",
                        Map.of("format", Map.of("type", "string", "description", "txt | jsonl | zip"),
                                "unique", Map.of("type", "boolean", "description",
                                        "txt only: each URL once, in order of its first visit"),
                                "include_results", Map.of("type", "boolean", "description",
                                        "txt only: also list the URLs of search results that were not opened")),
                        List.of("format"), this::export));
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
