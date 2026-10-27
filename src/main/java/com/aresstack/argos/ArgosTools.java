package com.aresstack.argos;

import com.aresstack.argos.history.SearchHistoryCapture;
import com.aresstack.argos.history.SearchHistoryTools;
import com.aresstack.argos.research.CamoufoxResearchPageReader;
import com.aresstack.argos.research.ResearchService;
import com.aresstack.argos.research.ResearchSettings;
import com.aresstack.argos.research.ResearchTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
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
    static final String VERSION = "0.1.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CamoufoxBrowser browser;
    private final WebSession web;
    /** Optional research orchestration; null unless ARGOS_RESEARCH=true. */
    private final ResearchTools research;
    private final ArgosBrowsingTools browsing;
    /** browser_download, browser_document, browser_open_many; null in tests without a document store. */
    private final DocumentAndBatchTools documents;
    /** Search History of this workspace's chat; null when it is off (single profile, ARGOS_SEARCH_HISTORY=false). */
    private final SearchHistoryCapture history;

    public ArgosTools(CamoufoxBrowser browser) {
        this(browser, new WebSession(browser, UrlPolicy.publicOnly(), null));
    }

    public ArgosTools(CamoufoxBrowser browser, WebSession web) {
        this(browser, web, ResearchSettings.disabled());
    }

    public ArgosTools(CamoufoxBrowser browser, WebSession web, ResearchSettings researchSettings) {
        this(browser, web, researchSettings, null);
    }

    public ArgosTools(CamoufoxBrowser browser, WebSession web, ResearchSettings researchSettings,
                      DocumentAndBatchTools documents) {
        this(browser, web, researchSettings, documents, null);
    }

    /**
     * With the chat's Search History: web_* pages are reported through {@link WebSession#onPageRead}, the
     * research reader and browser_navigate/browser_snapshot are recorded here. The search port inside {@code web}
     * must already be the capturing one (one event per search call, shared with web_research).
     */
    public ArgosTools(CamoufoxBrowser browser, WebSession web, ResearchSettings researchSettings,
                      DocumentAndBatchTools documents, SearchHistoryCapture history) {
        this.browser = browser;
        this.documents = documents;
        this.web = web;
        this.history = history;
        this.browsing = new ArgosBrowsingTools(browser);
        if (history != null) web.onPageRead(history.webPages());
        com.aresstack.argos.research.ResearchPageReader reader = new CamoufoxResearchPageReader(browser);
        if (history != null) reader = history.capturingReader(reader);
        this.research = researchSettings.enabled() && web.searchPort() != null
                ? new ResearchTools(new ResearchService(web.searchPort(), reader, researchSettings.budget()))
                : null;
    }

    /** The original nine browser_* tools (the {@link ToolProfile#LEGACY} profile). */
    public List<SyncToolSpecification> all() {
        return browserTools();
    }

    public List<SyncToolSpecification> forProfile(ToolProfile profile) {
        List<SyncToolSpecification> tools = new ArrayList<>(profileTools(profile));
        // Opt-in (ARGOS_RESEARCH=true): one-call research suits small models, so every profile gets it.
        if (research != null) tools.addAll(research.specifications());
        // Search History of the calling chat (per-chat profiles only); the legacy set stays unchanged.
        if (history != null && profile != ToolProfile.LEGACY) {
            tools.addAll(new SearchHistoryTools(history.history()).specifications());
        }
        return List.copyOf(tools);
    }

    private List<SyncToolSpecification> profileTools(ToolProfile profile) {
        return switch (profile) {
            case LEGACY -> browserTools();
            // Small models: only the short AskAI tools plus web_find, nothing else to choose from.
            case COMPACT -> webTools();
            case EXPERT -> {
                List<SyncToolSpecification> tools = new ArrayList<>(webTools());
                tools.addAll(browserTools());
                // Tabs, history, semantic controls, extraction. browsing.compact() (web_controls,
                // web_click, web_fill) joins COMPACT only after it has been tested with small models.
                tools.addAll(browsing.expert());
                tools.addAll(new ParallelPageTools(browser).expertTools());
                tools.addAll(expertTools());
                tools.addAll(new NetworkTools(browser).expertTools());
                if (documents != null) tools.addAll(documents.all());
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
                        + "An 'HTTP error:' line marks a page the server could not deliver; then try another page or result. "
                        + "Example: {\"url\": \"https://en.wikipedia.org/wiki/Firefox\"}",
                Map.of("url", field("string", "Full http(s) URL")), List.of("url"),
                args -> web.open(string(args, "url"))));
        tools.add(text("web_read", "Read the text of the page the browser shows, whichever tool opened it. "
                        + "Long pages come in parts: the answer says PART 1 of 3, then ask for the next part. "
                        + "Example: {\"part\": 2}",
                Map.of("part", field("integer", "Part number, default 1")), List.of(),
                args -> web.read(number(args, "part", 1))));
        tools.add(text("web_find", "Find a word or phrase on the page the browser shows without reading the whole page. "
                        + "Returns the number of matches and short snippets, each with the web_read part it is in. "
                        + "Example: {\"text\": \"opening hours\"}",
                Map.of("text", field("string", "Word or phrase to find; case does not matter")), List.of("text"),
                args -> web.find(string(args, "text"))));
        tools.add(text("web_links", "List the links of the page the browser shows as link-1, link-2, ... "
                        + "Use the id with web_follow. Call web_read first if the page loaded more content. Example: {}",
                Map.of(), List.of(), args -> web.links()));
        tools.add(text("web_follow", "Open a link by its id: link-3 from web_links or result-2 from web_search. "
                        + "A bare number uses the list you saw last. "
                        + "An 'HTTP error:' line marks a page the server could not deliver; then try another result or link. "
                        + "Example: {\"link_id\": \"link-3\"}",
                Map.of("link_id", field("string", "Id such as link-3 or result-2")), List.of("link_id"),
                args -> web.follow(string(args, "link_id"))));
        tools.add(text("web_back", "Go back to the previous page and return its text. Example: {}",
                Map.of(), List.of(), args -> web.back()));
        return List.copyOf(tools);
    }

    /** Browser tools added after the original nine; the legacy profile keeps its exact list. */
    public List<SyncToolSpecification> expertTools() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("browser_wait", "Wait until the page has finished loading (network quiet; stricter checks "
                        + "if configured). Optionally also wait for a visible element or text. "
                        + "Never fails on timeout; check readiness.state ('ready' or 'timeout') and readiness.pending. "
                        + "Example: {\"text\": \"Search results\", \"timeoutMs\": 10000}",
                Map.of("selector", field("string", "Optional CSS selector that must become visible, e.g. '#results'"),
                        "text", field("string", "Optional text that must become visible on the page"),
                        "timeoutMs", field("integer", "Maximum wait in milliseconds (500–60000, default 10000)"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of(), args -> browser.waitFor(optionalString(args, "selector"), optionalString(args, "text"),
                        number(args, "timeoutMs", 10_000), optionalString(args, "tab"))));
        return List.copyOf(tools);
    }

    /** The original browser_* tools. */
    public List<SyncToolSpecification> browserTools() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("browser_status", "Report whether Argos' dedicated Camoufox session is running.",
                Map.of(), List.of(), args -> browser.status()));
        tools.add(tool("browser_navigate", "Navigate the current tab or the specified tab id to an http(s) URL. Omit tab to keep current-tab behavior.",
                Map.of("url", field("string", "Full http(s) website URL"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("url"), args -> {
                    Map<String, Object> result = browser.navigate(string(args, "url"), optionalString(args, "tab"));
                    if (history != null) {
                        history.browserPage(optionalString(args, "tab"), string(args, "url"), true, "browser_navigate");
                    }
                    return result;
                }));
        tools.add(tool("browser_snapshot", "Read the current tab or the specified tab id as text. Works for HTML (visible text), "
                        + "XML/RSS/Atom feeds (numbered entries), JSON and plain text; binary documents such as "
                        + "PDF are reported with documentType 'binary' and a hint instead of text.",
                Map.of("maxCharacters", field("integer", "Maximum characters (1000–60000, default 16000)"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of(), args -> {
                    Object snapshot = browser.snapshot(number(args, "maxCharacters", 16_000), optionalString(args, "tab"));
                    if (history != null) history.browserPage(optionalString(args, "tab"), null, false, "browser_snapshot");
                    return snapshot;
                }));
        tools.add(tool("browser_inspect", "Inspect DOM elements matching a CSS selector in the current or specified tab, including text, links and attributes.",
                Map.of("selector", field("string", "CSS selector"),
                        "limit", field("integer", "Maximum elements (1–50, default 20)"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("selector"), args -> browser.inspect(string(args, "selector"),
                        number(args, "limit", 20), optionalString(args, "tab"))));
        tools.add(tool("browser_click", "Click the first element matching a CSS selector in the current or specified tab.",
                Map.of("selector", field("string", "CSS selector"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")), List.of("selector"),
                args -> browser.click(string(args, "selector"), optionalString(args, "tab"))));
        tools.add(tool("browser_fill", "Fill an input field in the current or specified tab, replacing its existing contents.",
                Map.of("selector", field("string", "CSS selector"), "text", field("string", "Text to enter"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("selector", "text"), args -> browser.fill(string(args, "selector"), string(args, "text"),
                        optionalString(args, "tab"))));
        tools.add(tool("browser_evaluate", "Evaluate JavaScript in the current or specified tab and return its result. This can change that page.",
                Map.of("expression", field("string", "JavaScript expression or function, e.g. () => document.title"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("expression"), args -> browser.evaluate(string(args, "expression"), optionalString(args, "tab"))));
        tools.add(tool("browser_network", "List recent requests and responses for the whole session or one tab, one line per request with an id "
                        + "(URLs have query parameters redacted). Optional filter, e.g. \"xhr\", \"json\", \"failed\" or \"api/\".",
                Map.of("filter", field("string", "Optional case-insensitive text matched against url, type, mime, kind, status"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2; page-scoped results omit proxy events that cannot be attributed to one tab")),
                List.of(), args -> browser.networkEvents(optionalString(args, "filter"), optionalString(args, "tab"))));
        tools.add(SyncToolSpecification.builder()
                .tool(Tool.builder().name("browser_screenshot")
                        .description("Take a PNG screenshot of the current Camoufox page or a specified tab. mode: viewport (default, "
                                + "what is on screen), full (whole page) or element (one control, needs target). "
                                + "mark=true draws the numbered controls into the image. A text part explains how "
                                + "image pixels map to viewport CSS pixels for mouse input. "
                                + "Example: {\"mode\": \"viewport\", \"mark\": true}")
                        .inputSchema(schema(Map.of(
                                "mode", field("string", "viewport | full | element"),
                                "target", field("string", "For mode element: control number, aria ref or locator, "
                                        + "e.g. 3 or role=button name=\"Suchen\""),
                                "mark", field("boolean", "Draw numbered boxes around the page's controls"),
                                "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                                List.of())).build())
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                        PageScreenshot shot = browser.capture(screenshotMode(optionalString(args, "mode")),
                                optionalString(args, "target"), flag(args, "mark"), optionalString(args, "tab"));
                        String encoded = Base64.getEncoder().encodeToString(shot.png());
                        return CallToolResult.builder()
                                .content(List.of(new ImageContent(null, encoded, "image/png"),
                                        new TextContent(shot.legend())))
                                .build();
                    } catch (Exception error) {
                        return failure(error);
                    }
                }).build());
        return List.copyOf(tools);
    }

    /** A tool answering in plain text, which small models read more reliably than JSON. */
    private static SyncToolSpecification text(String name, String description,
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

    static SyncToolSpecification tool(String name, String description,
                                      Map<String, Object> properties, List<String> required,
                                      Function<Map<String, Object>, Object> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description)
                        .inputSchema(schema(properties, required)).build())
                .callHandler((exchange, request) -> {
                    try {
                        Object response = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                        return CallToolResult.builder()
                                .addTextContent(JSON.writeValueAsString(response)).build();
                    } catch (Exception error) {
                        return failure(error);
                    }
                }).build();
    }

    /** Like {@link #tool}, but returns the handler's text unchanged: short readable lines for small models. */
    static SyncToolSpecification textTool(String name, String description,
                                          Map<String, Object> properties, List<String> required,
                                          Function<Map<String, Object>, String> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description)
                        .inputSchema(schema(properties, required)).build())
                .callHandler((exchange, request) -> {
                    try {
                        String response = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                        return CallToolResult.builder().addTextContent(response).build();
                    } catch (Exception error) {
                        return failure(error);
                    }
                }).build();
    }

    static PageScreenshot.Mode screenshotMode(String mode) {
        if (mode == null) return PageScreenshot.Mode.VIEWPORT;
        return switch (mode.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "viewport", "screen", "visible" -> PageScreenshot.Mode.VIEWPORT;
            case "full", "fullpage", "full_page", "page" -> PageScreenshot.Mode.FULL;
            case "element", "control" -> PageScreenshot.Mode.ELEMENT;
            default -> throw new IllegalArgumentException("Unknown mode \"" + mode + "\"; use viewport, full or element.");
        };
    }

    static JsonSchema schema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, false, Map.of(), Map.of());
    }

    static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    static String string(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Missing string field: " + name);
        }
        return text;
    }

    static boolean flag(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        return value instanceof Boolean b ? b : value instanceof String text && Boolean.parseBoolean(text);
    }

    static int number(Map<String, Object> args, String field, int fallback) {
        Object value = args.get(field);
        if (value instanceof Number n) return n.intValue();
        // Small models often send numbers as strings ("2").
        if (value instanceof String text && text.strip().matches("\\d{1,6}")) return Integer.parseInt(text.strip());
        return fallback;
    }

    static String optionalString(Map<String, Object> arguments, String name) {
        return arguments.get(name) instanceof String text && !text.isBlank() ? text : null;
    }

    static CallToolResult failure(Exception error) {
        String safeMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return CallToolResult.builder().isError(true).addTextContent(safeMessage).build();
    }
}
