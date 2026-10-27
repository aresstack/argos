package com.aresstack.argos;

import com.aresstack.argos.convenience.ConvenienceSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aresstack.argos.ArgosTools.field;
import static com.aresstack.argos.ArgosTools.flag;
import static com.aresstack.argos.ArgosTools.number;
import static com.aresstack.argos.ArgosTools.optionalString;
import static com.aresstack.argos.ArgosTools.string;
import static com.aresstack.argos.ArgosTools.tool;

/**
 * web_screenshot (convenience and developer) and the developer profile's page and network diagnosis. They reuse
 * the expert implementations (capture, inspect, extract, NetworkRecorder) but address only tabs opened by the
 * convenience tools, by the same tab ids; the current browser tab is never used implicitly.
 */
final class TabDiagnosticTools {
    static final List<String> DEVELOPER_NAMES = List.of("web_evaluate", "web_inspect", "web_extract", "web_network",
            "web_network_detail", "web_network_json", "web_network_har", "web_network_clear");
    static final int MAX_RESULT_CHARACTERS = 60_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TAB = "Tab ID from a page response, e.g. t2";

    private final CamoufoxBrowser browser;
    private final ConvenienceSession session;

    TabDiagnosticTools(CamoufoxBrowser browser, ConvenienceSession session) {
        this.browser = browser;
        this.session = session;
    }

    SyncToolSpecification screenshot() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name("web_screenshot")
                        .description("Take a PNG screenshot of a tab. mode: viewport (default, what is on screen) or full (whole page).")
                        .inputSchema(ArgosTools.schema(Map.of("tabId", field("string", TAB),
                                "mode", field("string", "viewport | full")), List.of("tabId"))).build())
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                        PageScreenshot.Mode mode = ArgosTools.screenshotMode(optionalString(args, "mode"));
                        if (mode == PageScreenshot.Mode.ELEMENT) throw new IllegalArgumentException("Use viewport or full.");
                        PageScreenshot shot = browser.capture(mode, null, false, tab(args));
                        return CallToolResult.builder()
                                .content(List.of(new ImageContent(null, Base64.getEncoder().encodeToString(shot.png()),
                                        "image/png"), new TextContent(shot.legend())))
                                .build();
                    } catch (Exception error) {
                        return ArgosTools.failure(error);
                    }
                }).build();
    }

    List<SyncToolSpecification> developer() {
        return List.of(
                tool("web_evaluate", "Run JavaScript in one tab and return its JSON result (cut at maxCharacters). "
                                + "Errors come back as ok=false; navigated=true if the script left the page. "
                                + "Example: {\"tabId\":\"t2\",\"expression\":\"() => document.title\"}",
                        Map.of("tabId", field("string", TAB),
                                "expression", field("string", "JavaScript expression or function"),
                                "maxCharacters", field("integer", "Result limit (100-60000, default 8000)")),
                        List.of("tabId", "expression"), args -> evaluate(tab(args), string(args, "expression"),
                                number(args, "maxCharacters", 8_000))),
                tool("web_inspect", "List DOM elements matching a CSS selector in a tab: tag, text, id, classes, attributes. "
                                + "Use it to test selectors.",
                        Map.of("tabId", field("string", TAB), "selector", field("string", "CSS selector"),
                                "limit", field("integer", "Maximum elements (1-50, default 20)")),
                        List.of("tabId", "selector"), args -> browser.inspect(string(args, "selector"),
                                number(args, "limit", 20), tab(args))),
                tool("web_extract", "Extract page structure from a tab. kind: overview (default), headings, links, tables, "
                                + "lists, metadata, jsonld.",
                        Map.of("tabId", field("string", TAB),
                                "kind", field("string", "overview | headings | links | tables | lists | metadata | jsonld"),
                                "scope", field("string", "Optional landmark, e.g. main or article")),
                        List.of("tabId"), args -> browser.extract(optionalString(args, "kind"),
                                optionalString(args, "scope"), tab(args))),
                tool("web_network", "List recorded requests, one row per request with id, method, status, type and MIME. "
                                + "filter matches URL, method, type, MIME or status, e.g. \"json\", \"POST\", \"404\", \"failed\".",
                        Map.of("tabId", field("string", "Optional tab ID; omit for all tabs"),
                                "filter", field("string", "Optional case-insensitive text")),
                        List.of(), args -> browser.networkEvents(optionalString(args, "filter"), optionalTab(args))),
                tool("web_network_detail", "Show one request by id: URL, status, timing, request/response headers, POST "
                                + "data; body=true adds the text body if available. Secrets are masked.",
                        Map.of("id", field("string", "Request id from web_network, e.g. n12"),
                                "body", field("boolean", "Include the text response body"),
                                "tabId", field("string", "Optional tab ID; ids of other tabs are rejected")),
                        List.of("id"), args -> browser.networkDetail(string(args, "id"), flag(args, "body"), optionalTab(args))),
                tool("web_network_json", "List JSON/API responses, newest first, with id, URL and data shape, e.g. "
                                + "{results[20], total}. Read one with web_network_detail body=true.",
                        Map.of("tabId", field("string", "Optional tab ID; omit for all tabs"),
                                "filter", field("string", "Optional text the URL must contain"),
                                "limit", field("integer", "Maximum responses (1-20, default 10)")),
                        List.of(), args -> browser.networkJson(optionalString(args, "filter"),
                                number(args, "limit", 10), optionalTab(args))),
                tool("web_network_har", "Export the newest recorded requests as HAR 1.2 JSON with masked secrets. "
                                + "Contains only bodies already read; binary bodies are never included.",
                        Map.of("tabId", field("string", "Optional tab ID; omit for all tabs"),
                                "limit", field("integer", "Newest entries (1-200, default 50)")),
                        List.of(), args -> browser.networkHar(number(args, "limit", 50), optionalTab(args))),
                tool("web_network_clear", "Forget the recorded requests before an action you want to observe alone. "
                                + "The Search History is not affected.",
                        Map.of(), List.of(), args -> browser.clearNetwork()));
    }

    Map<String, Object> evaluate(String tab, String expression, int maxCharacters) {
        int limit = Math.max(100, Math.min(MAX_RESULT_CHARACTERS, maxCharacters));
        Map<String, Object> answer = new LinkedHashMap<>(browser.evaluateInTab(expression, tab));
        answer.put("tabId", tab);
        if (answer.containsKey("result")) {
            String json;
            try {
                json = JSON.writeValueAsString(answer.get("result"));
            } catch (Exception error) {
                json = String.valueOf(answer.get("result"));
            }
            if (json.length() > limit) {
                answer.put("result", json.substring(0, limit));
                answer.put("truncated", true);
                answer.put("resultCharacters", json.length());
            }
        }
        return answer;
    }

    private String tab(Map<String, Object> args) {
        return session.requireTab(string(args, "tabId"));
    }

    private String optionalTab(Map<String, Object> args) {
        String tab = optionalString(args, "tabId");
        return tab == null || tab.isBlank() ? null : session.requireTab(tab);
    }
}
