package com.aresstack.argos;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import com.aresstack.argos.web.UrlPolicy;
import com.aresstack.argos.web.WebSession;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MCP definitions and small adapters; browser operations live in CamoufoxBrowser and
 * WebSession. Which tools are published depends on the configured {@link ToolProfile}.
 */
public final class ArgosTools {
    private final CamoufoxBrowser browser;
    private final WebSession web;
    private final ObjectMapper json = new ObjectMapper();

    public ArgosTools(CamoufoxBrowser browser) {
        this(browser, new WebSession(browser, new UrlPolicy(false), null));
    }

    public ArgosTools(CamoufoxBrowser browser, WebSession web) {
        this.browser = browser;
        this.web = web;
    }

    /** The original nine browser_* tools (the {@link ToolProfile#LEGACY} profile). */
    public List<SyncToolSpecification> all() {
        return browserTools();
    }

    public List<SyncToolSpecification> forProfile(ToolProfile profile) {
        return switch (profile) {
            case LEGACY -> browserTools();
            // Small models: only the six short AskAI tools, nothing else to choose from.
            case COMPACT -> webTools();
            case EXPERT -> {
                List<SyncToolSpecification> tools = new ArrayList<>(webTools());
                tools.addAll(browserTools());
                yield List.copyOf(tools);
            }
        };
    }

    /**
     * The compact AskAI tool set: one task per tool, at most one parameter, plain
     * numbered text answers. Names and parameters match AskAI's browser MCP server.
     */
    public List<SyncToolSpecification> webTools() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(text("web_search", "Search the web. Returns results result-1, result-2, ... with title, url and snippet. "
                        + "Example: {\"query\": \"camoufox webdriver bidi\"}",
                Map.of("query", field("string", "What to search for")), List.of("query"),
                args -> web.search(string(args, "query"))));
        tools.add(text("web_open", "Open a web page and return its title and text. "
                        + "Example: {\"url\": \"https://en.wikipedia.org/wiki/Firefox\"}",
                Map.of("url", field("string", "Full http(s) URL")), List.of("url"),
                args -> web.open(string(args, "url"))));
        tools.add(text("web_read", "Read the text of the current page again. Long pages come in parts: "
                        + "the answer says PART 1 of 3, then ask for the next part. Example: {\"part\": 2}",
                Map.of("part", field("integer", "Part number, default 1")), List.of(),
                args -> web.read(number(args, "part", 1))));
        tools.add(text("web_links", "List the links of the current page as link-1, link-2, ... "
                        + "Use the id with web_follow. Call web_read first if the page loaded more content. Example: {}",
                Map.of(), List.of(), args -> web.links()));
        tools.add(text("web_follow", "Open a link by its id: link-3 from web_links or result-2 from web_search. "
                        + "A bare number uses the list you saw last. Example: {\"link_id\": \"link-3\"}",
                Map.of("link_id", field("string", "Id such as link-3 or result-2")), List.of("link_id"),
                args -> web.follow(string(args, "link_id"))));
        tools.add(text("web_back", "Go back to the previous page and return its text. Example: {}",
                Map.of(), List.of(), args -> web.back()));
        return List.copyOf(tools);
    }

    /** The original browser_* tools. */
    public List<SyncToolSpecification> browserTools() {
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

    /** A tool answering in plain text, which small models read more reliably than JSON. */
    private SyncToolSpecification text(String name, String description,
                                      Map<String, Object> properties, List<String> required,
                                      TextHandler handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description)
                        .inputSchema(schema(properties, required)).build())
                .callHandler((exchange, request) -> {
                    try {
                        String answer = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                        return CallToolResult.builder().addTextContent(answer).build();
                    } catch (Exception error) {
                        return failure(error);
                    }
                }).build();
    }

    @FunctionalInterface
    private interface TextHandler {
        String apply(Map<String, Object> arguments) throws Exception;
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
        if (value instanceof Number n) return n.intValue();
        // Small models often send numbers as strings ("2").
        if (value instanceof String text && text.strip().matches("\\d{1,6}")) return Integer.parseInt(text.strip());
        return fallback;
    }

    private static CallToolResult failure(Exception error) {
        String safeMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return CallToolResult.builder().isError(true).addTextContent(safeMessage).build();
    }
}
