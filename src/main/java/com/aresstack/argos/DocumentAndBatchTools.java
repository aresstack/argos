package com.aresstack.argos;

import com.aresstack.argos.fetch.FetchFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Expert-profile MCP tools for documents and batches. Not part of the nine legacy {@code browser_*} tools;
 * they are published only when the expert tool profile is selected.
 */
public final class DocumentAndBatchTools {
    private final ResearchDocuments documents;
    private final ObjectMapper json = new ObjectMapper();

    public DocumentAndBatchTools(ResearchDocuments documents) {
        this.documents = documents;
    }

    public List<SyncToolSpecification> all() {
        return List.of(
                tool("browser_download", """
                        Download a document (PDF, Word, Excel, PowerPoint, OpenDocument, CSV, TXT, XML, JSON, HTML) \
                        through the browser session in a background tab. The current page stays open. Returns a \
                        downloadId; read the text with browser_document. Relative links are resolved against the \
                        current page. Example: {"url": "https://arxiv.org/pdf/1706.03762"}""",
                        Map.of("url", field("string", "Document URL, absolute or relative to the current page")),
                        List.of("url"), args -> documents.download(string(args, "url")).metadata()),
                tool("browser_document", """
                        Read the text of a document downloaded with browser_download. \
                        Example: {"downloadId": "doc-1", "maxCharacters": 20000}""",
                        Map.of("downloadId", field("string", "Id returned by browser_download, e.g. doc-1"),
                                "maxCharacters", field("integer", "Maximum characters (1000-100000, default 20000)")),
                        List.of("downloadId"),
                        args -> documents.read(string(args, "downloadId"), number(args, "maxCharacters", 20_000))),
                tool("browser_open_many", """
                        Open up to 10 http(s) URLs in background tabs (at most 4 at a time), read each page or \
                        document and close the tabs again. The current page stays open. One result per URL with \
                        its own status; a failing URL does not affect the others; duplicates are skipped. \
                        Example: {"urls": ["https://example.org/a", "https://example.org/b.pdf"]}""",
                        Map.of("urls", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "1 to 10 absolute http(s) URLs"),
                                "maxCharactersPerPage", field("integer",
                                        "Maximum characters per result (200-20000, default 4000)")),
                        List.of("urls"),
                        args -> documents.openMany(strings(args, "urls"),
                                Math.min(20_000, number(args, "maxCharactersPerPage", 4_000)))));
    }

    private SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
                                       List<String> required, Function<Map<String, Object>, Object> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description)
                        .inputSchema(new JsonSchema("object", properties, required, false, Map.of(), Map.of()))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        Object response = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                        return CallToolResult.builder().addTextContent(json.writeValueAsString(response)).build();
                    } catch (FetchFailure failure) {
                        return error(failure.kind().name() + ": " + failure.getMessage());
                    } catch (IllegalArgumentException invalid) {
                        return error("INVALID_REQUEST: " + invalid.getMessage());
                    } catch (Exception failure) {
                        return error("FAILED: " + (failure.getMessage() == null
                                ? failure.getClass().getSimpleName() : failure.getMessage()));
                    }
                }).build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    private static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static String string(Map<String, Object> arguments, String name) {
        if (!(arguments.get(name) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing string field: " + name);
        }
        return text;
    }

    private static List<String> strings(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value instanceof String single && !single.isBlank()) return List.of(single);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Missing URL list: " + name);
        return list.stream().map(item -> item == null ? "" : item.toString()).toList();
    }

    private static int number(Map<String, Object> arguments, String name, int fallback) {
        return arguments.get(name) instanceof Number number ? number.intValue() : fallback;
    }
}
