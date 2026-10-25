package com.aresstack.argos;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** MCP definitions and small adapters; browser operations live in CamoufoxBrowser. */
public final class ArgosTools {
    private final CamoufoxBrowser browser;
    private final ObjectMapper json = new ObjectMapper();

    public ArgosTools(CamoufoxBrowser browser) {
        this.browser = browser;
    }

    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("browser_status", "Report whether Argos' dedicated Camoufox session is running.",
                Map.of(), List.of(), args -> browser.status()));
        tools.add(tool("browser_navigate", "Navigate the current Camoufox page to an http(s) URL.",
                Map.of("url", field("string", "Full http(s) website URL")), List.of("url"),
                args -> browser.navigate(string(args, "url"))));
        tools.add(tool("browser_snapshot", "Read current page title, URL and visible DOM text.",
                Map.of("maxCharacters", field("integer", "Maximum characters (1000–60000, default 16000)")),
                List.of(), args -> browser.snapshot(number(args, "maxCharacters", 16_000))));
        tools.add(tool("browser_inspect", "Inspect DOM elements matching a CSS selector, including text, links and attributes.",
                Map.of("selector", field("string", "CSS selector"),
                        "limit", field("integer", "Maximum elements (1–50, default 20)")),
                List.of("selector"), args -> browser.inspect(string(args, "selector"), number(args, "limit", 20))));
        tools.add(tool("browser_click", "Click the first element matching a CSS selector.",
                Map.of("selector", field("string", "CSS selector")), List.of("selector"),
                args -> browser.click(string(args, "selector"))));
        tools.add(tool("browser_fill", "Fill an input field with text, replacing its existing contents.",
                Map.of("selector", field("string", "CSS selector"), "text", field("string", "Text to enter")),
                List.of("selector", "text"), args -> browser.fill(string(args, "selector"), string(args, "text"))));
        tools.add(tool("browser_evaluate", "Evaluate JavaScript in the current website and return its result. This can change the page.",
                Map.of("expression", field("string", "JavaScript expression or function, e.g. () => document.title")),
                List.of("expression"), args -> browser.evaluate(string(args, "expression"))));
        tools.add(tool("browser_network", "List recent page requests and responses (URLs have query parameters redacted).",
                Map.of(), List.of(), args -> browser.networkEvents()));
        tools.add(SyncToolSpecification.builder()
                .tool(Tool.builder().name("browser_screenshot")
                        .description("Take a PNG screenshot of the current Camoufox page.")
                        .inputSchema(schema(Map.of(), List.of())).build())
                .callHandler((exchange, request) -> {
                    try {
                        String encoded = Base64.getEncoder().encodeToString(browser.screenshot());
                        return CallToolResult.builder()
                                .content(List.of(new ImageContent(null, encoded, "image/png")))
                                .build();
                    } catch (Exception error) {
                        return failure(error);
                    }
                }).build());
        return List.copyOf(tools);
    }

    private SyncToolSpecification tool(String name, String description,
                                      Map<String, Object> properties, List<String> required,
                                      Function<Map<String, Object>, Object> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description)
                        .inputSchema(schema(properties, required)).build())
                .callHandler((exchange, request) -> {
                    try {
                        Object response = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                        return CallToolResult.builder()
                                .addTextContent(json.writeValueAsString(response)).build();
                    } catch (Exception error) {
                        return failure(error);
                    }
                }).build();
    }

    private static JsonSchema schema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, false, Map.of(), Map.of());
    }

    private static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static String string(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Missing string field: " + name);
        }
        return text;
    }

    private static int number(Map<String, Object> args, String field, int fallback) {
        Object value = args.get(field);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static CallToolResult failure(Exception error) {
        String safeMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return CallToolResult.builder().isError(true).addTextContent(safeMessage).build();
    }
}
