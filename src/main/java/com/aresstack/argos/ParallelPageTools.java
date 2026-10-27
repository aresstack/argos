package com.aresstack.argos;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Expert-profile tools for bounded operations across existing browser tabs. */
public final class ParallelPageTools {
    private final CamoufoxBrowser browser;
    private final ObjectMapper json = new ObjectMapper();

    public ParallelPageTools(CamoufoxBrowser browser) {
        this.browser = browser;
    }

    public List<SyncToolSpecification> expertTools() {
        return List.of(
                tool("browser_pages_navigate",
                        "Navigate 1-10 distinct existing tabs in one call. Starts up to 4 navigations to response commit, "
                                + "then waits for each page; document loading can overlap. Results remain in input order. "
                                + "A failed or timed-out page gets its own error result; other pages continue. "
                                + "Do not include a tab more than once.",
                        Map.of("pages", Map.of("type", "array",
                                        "description", "1-10 objects with a distinct tab id and absolute http(s) URL",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "tab", field("string", "Existing tab id from browser_tabs, e.g. t2"),
                                                        "url", field("string", "Absolute http(s) URL to navigate to")),
                                                "required", List.of("tab", "url"),
                                                "additionalProperties", false)),
                                "parallel", field("integer", "Maximum in-flight pages (1-4, default 4)"),
                                "timeoutMs", field("integer", "Per-page timeout in milliseconds for response commit and for document load (500-20000, default 10000)")),
                        List.of("pages"), args -> browser.navigatePages(pageRequests(args),
                                number(args, "parallel", 4), number(args, "timeoutMs", 10_000))),
                tool("browser_pages_snapshot",
                        "Read snapshots from 1-10 existing tabs in one call. Each result identifies its tab and URL; "
                                + "a missing or unreadable tab produces an error for that tab only.",
                        Map.of("tabs", stringArray("Distinct tab ids from browser_tabs, e.g. [\"t1\", \"t2\"]"),
                                "maxCharacters", field("integer", "Maximum characters per snapshot (1000-60000, default 16000)")),
                        List.of("tabs"), args -> browser.snapshotPages(strings(args, "tabs"),
                                number(args, "maxCharacters", 16_000))),
                tool("browser_pages_inspect",
                        "Inspect the same CSS selector in 1-10 existing tabs in one read-only batch. Each result identifies "
                                + "its tab and URL; a missing or unreadable tab produces an error for that tab only.",
                        Map.of("tabs", stringArray("Distinct tab ids from browser_tabs, e.g. [\"t1\", \"t2\"]"),
                                "selector", field("string", "CSS selector to inspect in each tab"),
                                "limit", field("integer", "Maximum elements per tab (1-50, default 20)")),
                        List.of("tabs", "selector"), args -> browser.inspectPages(strings(args, "tabs"),
                                string(args, "selector"), number(args, "limit", 20)))
        );
    }

    private SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
                                       List<String> required, Function<Map<String, Object>, Object> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description)
                        .inputSchema(new JsonSchema("object", properties, required, false, Map.of(), Map.of()))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                        return CallToolResult.builder().addTextContent(json.writeValueAsString(handler.apply(arguments))).build();
                    } catch (IllegalArgumentException invalid) {
                        return error("INVALID_REQUEST: " + invalid.getMessage());
                    } catch (Exception failure) {
                        return error("FAILED: " + errorMessage(failure));
                    }
                }).build();
    }

    private static List<CamoufoxBrowser.PageNavigationRequest> pageRequests(Map<String, Object> arguments) {
        Object value = arguments.get("pages");
        if (!(value instanceof List<?> pages) || pages.isEmpty() || pages.size() > 10) {
            throw new IllegalArgumentException("Give between 1 and 10 page objects");
        }
        List<CamoufoxBrowser.PageNavigationRequest> requests = new ArrayList<>(pages.size());
        for (Object item : pages) {
            if (!(item instanceof Map<?, ?> page)) {
                throw new IllegalArgumentException("Each pages item must contain tab and url strings");
            }
            Object tab = page.get("tab");
            Object url = page.get("url");
            if (!(tab instanceof String tabId) || tabId.isBlank()
                    || !(url instanceof String targetUrl)) {
                throw new IllegalArgumentException("Each pages item must contain a non-blank tab and a URL string");
            }
            requests.add(new CamoufoxBrowser.PageNavigationRequest(tabId, targetUrl));
        }
        return List.copyOf(requests);
    }

    private static List<String> strings(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > 10) {
            throw new IllegalArgumentException("Give between 1 and 10 tab ids in " + name);
        }
        List<String> tabIds = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String tabId) || tabId.isBlank()) {
                throw new IllegalArgumentException("Every item in " + name + " must be a non-blank tab id");
            }
            tabIds.add(tabId);
        }
        return List.copyOf(tabIds);
    }

    private static String string(Map<String, Object> arguments, String name) {
        if (!(arguments.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("Missing string field: " + name);
        }
        return value;
    }

    private static int number(Map<String, Object> arguments, String name, int fallback) {
        return arguments.get(name) instanceof Number number ? number.intValue() : fallback;
    }

    private static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> stringArray(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }

    private static String errorMessage(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }
}
