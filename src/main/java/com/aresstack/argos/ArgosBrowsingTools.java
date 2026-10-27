package com.aresstack.argos;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.aresstack.argos.ArgosTools.field;
import static com.aresstack.argos.ArgosTools.number;
import static com.aresstack.argos.ArgosTools.optionalString;
import static com.aresstack.argos.ArgosTools.string;
import static com.aresstack.argos.ArgosTools.textTool;
import static com.aresstack.argos.ArgosTools.tool;

/**
 * Tabs, history, semantic controls and structured extraction.
 *
 * <p>{@link #expert()} is meant for large models (JSON results, refs, locators, tabs); {@link #compact()} holds
 * the single-purpose {@code web_*} interaction tools for small local models (one or two parameters, short
 * numbered text). Which list a server publishes is decided by the tool profile.
 */
public final class ArgosBrowsingTools {
    private static final String TARGET_HELP = "A control number from the control list (e.g. 3), an aria ref "
            + "(e.g. e12), a semantic locator (e.g. role=button name=\"Suchen\", label=\"E-Mail\", "
            + "placeholder=\"Search\", text=\"Weiter\") or simply the visible name (e.g. Suchen).";

    private static final String POINT_HELP = "Coordinates are CSS pixels in the browser viewport (the visible part "
            + "of the page, as in browser_screenshot): x=0, y=0 is its top left corner and does not move when the page "
            + "scrolls.";

    private final CamoufoxBrowser browser;

    public ArgosBrowsingTools(CamoufoxBrowser browser) {
        this.browser = browser;
    }

    public List<SyncToolSpecification> expert() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("browser_tabs", "List open tabs (id, URL, title); the current tab is marked.",
                Map.of(), List.of(), args -> browser.tabs()));
        tools.add(tool("browser_tab_new", "Open a new tab, make it current and optionally load an http(s) URL.",
                Map.of("url", field("string", "Optional URL, e.g. https://example.org")), List.of(),
                args -> browser.newTab(optionalString(args, "url"))));
        tools.add(tool("browser_tab_select", "Make a tab current. Later browser tools act on it.",
                Map.of("tab", field("string", "Tab id from browser_tabs, e.g. t2")), List.of("tab"),
                args -> browser.selectTab(string(args, "tab"))));
        tools.add(tool("browser_tab_close", "Close a tab. The last remaining tab cannot be closed.",
                Map.of("tab", field("string", "Tab id from browser_tabs, e.g. t2")), List.of("tab"),
                args -> browser.closeTab(string(args, "tab"))));
        tools.add(tool("browser_back", "Go back one page in the current or specified tab's history.",
                Map.of("tab", field("string", "Optional tab id from browser_tabs, e.g. t2")), List.of(),
                args -> browser.historyBack(optionalString(args, "tab"))));
        tools.add(tool("browser_forward", "Go forward one page in the current or specified tab's history.",
                Map.of("tab", field("string", "Optional tab id from browser_tabs, e.g. t2")), List.of(),
                args -> browser.forward(optionalString(args, "tab"))));
        tools.add(tool("browser_reload", "Reload the current or specified tab.",
                Map.of("tab", field("string", "Optional tab id from browser_tabs, e.g. t2")), List.of(),
                args -> browser.reload(optionalString(args, "tab"))));
        tools.add(tool("browser_controls",
                "List visible, enabled controls on the current or specified tab (fields, buttons, checkboxes, selects, tabs, menu items) with "
                        + "number, aria ref and a reusable semantic locator.",
                Map.of("filter", field("string", "Optional text the role, name or value must contain"),
                        "limit", field("integer", "Maximum controls (1-200, default 60)"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of(), args -> controlsAsJson(optionalString(args, "filter"),
                        Math.min(200, Math.max(1, number(args, "limit", 60))), optionalString(args, "tab"))));
        tools.add(tool("browser_click_target",
                "Click an element in the current or specified tab with real mouse input at its on-screen position. " + TARGET_HELP,
                Map.of("target", field("string", "What to click, e.g. role=button name=\"Anmelden\""),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("target"), args -> interactionAsJson(browser.clickTarget(
                        string(args, "target"), optionalString(args, "tab")))));
        tools.add(tool("browser_fill_target",
                "Click into a field in the current or specified tab and type text with real key presses, replacing its content. End the text "
                        + "with a newline to press Enter. " + TARGET_HELP,
                Map.of("target", field("string", "Which field, e.g. label=\"Suche\""),
                        "text", field("string", "Text to type"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("target", "text"),
                args -> interactionAsJson(browser.fillTarget(
                        string(args, "target"), string(args, "text"), optionalString(args, "tab")))));
        tools.add(tool("browser_mouse_move",
                "Move the mouse pointer to a point in the current or specified tab without clicking. " + POINT_HELP,
                Map.of("x", field("number", "Horizontal position in CSS pixels from the viewport's left edge"),
                        "y", field("number", "Vertical position in CSS pixels from the viewport's top edge"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("x", "y"), args -> browser.mouseMove(decimal(args, "x"), decimal(args, "y"),
                        optionalString(args, "tab"))));
        tools.add(tool("browser_mouse_click",
                "Click with real mouse input at a point in the current or specified tab. Prefer browser_click_target "
                        + "when the element is a listed control; use this for canvases, maps or spots without a name. "
                        + POINT_HELP,
                Map.of("x", field("number", "Horizontal position in CSS pixels from the viewport's left edge"),
                        "y", field("number", "Vertical position in CSS pixels from the viewport's top edge"),
                        "button", field("string", "left (default), middle or right"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("x", "y"), args -> browser.mouseClick(decimal(args, "x"), decimal(args, "y"),
                        optionalString(args, "button"), optionalString(args, "tab"))));
        tools.add(tool("browser_hover",
                "Rest the mouse pointer on an element in the current or specified tab without clicking, e.g. to open a "
                        + "hover menu or show a tooltip. Scrolls it into view with the mouse wheel if needed. " + TARGET_HELP,
                Map.of("target", field("string", "What to hover, e.g. role=menuitem name=\"Produkte\""),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("target"), args -> browser.hover(string(args, "target"), optionalString(args, "tab"))));
        tools.add(tool("browser_scroll",
                "Turn the mouse wheel in the current or specified tab. Positive delta_y scrolls down, negative up; "
                        + "positive delta_x scrolls right, negative left (CSS pixels). The element "
                        + "under the pointer scrolls: give a target or x/y to scroll a list or panel instead of the page. "
                        + POINT_HELP,
                Map.of("delta_x", field("number", "Horizontal wheel distance in CSS pixels (default 0)"),
                        "delta_y", field("number", "Vertical wheel distance in CSS pixels (default 0)"),
                        "target", field("string", "Optional element to scroll over. " + TARGET_HELP),
                        "x", field("number", "Optional pointer x in CSS pixels; requires y"),
                        "y", field("number", "Optional pointer y in CSS pixels; requires x"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of(), args -> browser.scroll(optionalDecimal(args, "delta_x", 0), optionalDecimal(args, "delta_y", 0),
                        args.get("x") == null ? null : decimal(args, "x"),
                        args.get("y") == null ? null : decimal(args, "y"),
                        optionalString(args, "target"), optionalString(args, "tab"))));
        tools.add(tool("browser_drag",
                "Drag and drop with real mouse input in the current or specified tab: press on the source, move, "
                        + "release on the target, e.g. to sort a list, move a card or drop a file entry onto a folder. "
                        + "Give the source as source or from_x/from_y and the target as target or to_x/to_y. "
                        + TARGET_HELP + " " + POINT_HELP,
                Map.of("source", field("string", "Element to pick up, e.g. text=\"Karte 1\""),
                        "target", field("string", "Element to drop on, e.g. role=region name=\"Erledigt\""),
                        "from_x", field("number", "Start x in CSS pixels instead of source; requires from_y"),
                        "from_y", field("number", "Start y in CSS pixels instead of source; requires from_x"),
                        "to_x", field("number", "End x in CSS pixels instead of target; requires to_y"),
                        "to_y", field("number", "End y in CSS pixels instead of target; requires to_x"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of(), args -> browser.drag(optionalString(args, "source"),
                        args.get("from_x") == null ? null : decimal(args, "from_x"),
                        args.get("from_y") == null ? null : decimal(args, "from_y"),
                        optionalString(args, "target"),
                        args.get("to_x") == null ? null : decimal(args, "to_x"),
                        args.get("to_y") == null ? null : decimal(args, "to_y"),
                        optionalString(args, "tab"))));
        tools.add(tool("browser_press_key",
                "Press a key or shortcut in the current or specified tab with real key events, e.g. Enter, Escape, Tab, ArrowDown, PageDown, "
                        + "Control+a, Shift+Tab. Optionally click into a target first; otherwise the focused element "
                        + "gets the keys. To type text, use browser_fill_target. " + TARGET_HELP,
                Map.of("keys", field("string", "Key or combination, e.g. Escape or Control+Enter"),
                        "target", field("string", "Optional element to focus first, e.g. label=\"Suche\""),
                        "times", field("integer", "How often to press it (1-20, default 1)"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of("keys"), args -> interactionAsJson(browser.pressKey(optionalString(args, "target"),
                        string(args, "keys"), number(args, "times", 1), optionalString(args, "tab")))));
        tools.add(tool("browser_extract",
                "Extract structure from the current or specified tab's HTML page. kind: overview (default), headings, links, tables, "
                        + "lists, metadata (description, Open Graph, citation_* tags), jsonld.",
                Map.of("kind", field("string", "overview | headings | links | tables | lists | metadata | jsonld"),
                        "scope", field("string", "Optional landmark role or name limiting headings/links/tables/"
                                + "lists, e.g. main, article, navigation"),
                        "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                List.of(), args -> browser.extract(optionalString(args, "kind"), optionalString(args, "scope"),
                        optionalString(args, "tab"))));
        return List.copyOf(tools);
    }

    public List<SyncToolSpecification> compact() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(textTool("web_controls",
                "List the buttons and input fields of the current page as a numbered list. "
                        + "Example: web_controls with filter \"such\" shows only search fields and buttons.",
                Map.of("filter", field("string", "Optional word to look for, e.g. search")), List.of(),
                args -> plain(() -> controlsAsText(optionalString(args, "filter")))));
        tools.add(textTool("web_click",
                "Click a button, checkbox or link. Use the number from web_controls or the visible name. "
                        + "Examples: web_click 3, web_click \"Suchen\".",
                Map.of("target", field("string", "Number from web_controls or the visible name, e.g. 3")),
                List.of("target"), args -> plain(() -> interactionAsText("Clicked",
                        browser.clickTarget(string(args, "target"))))));
        tools.add(textTool("web_fill",
                "Type text into an input field, replacing what is there. Use the number from web_controls. "
                        + "Example: web_fill 2 \"climate change\". End the text with a newline to press Enter.",
                Map.of("target", field("string", "Number from web_controls or the field's name, e.g. 2"),
                        "text", field("string", "Text to type")),
                List.of("target", "text"), args -> plain(() -> interactionAsText("Typed into",
                        browser.fillTarget(string(args, "target"), string(args, "text"))))));
        return List.copyOf(tools);
    }

    /** A required finite number; small models often send numbers as strings ("120"). */
    static double decimal(Map<String, Object> args, String name) {
        Object value = args.get(name);
        double number;
        if (value instanceof Number n) {
            number = n.doubleValue();
        } else if (value instanceof String text && text.strip().matches("-?\\d{1,7}(\\.\\d+)?")) {
            number = Double.parseDouble(text.strip());
        } else throw new IllegalArgumentException("Missing number field: " + name);
        if (!Double.isFinite(number)) throw new IllegalArgumentException(name + " must be a finite number");
        return number;
    }

    static double optionalDecimal(Map<String, Object> args, String name, double fallback) {
        return args.get(name) == null ? fallback : decimal(args, name);
    }

    private Map<String, Object> controlsAsJson(String filter, int limit) {
        return controlsAsJson(filter, limit, null);
    }

    private Map<String, Object> controlsAsJson(String filter, int limit, String tabId) {
        PageControls controls = browser.controls(tabId);
        List<Map<String, Object>> list = controls.filtered(filter, limit).stream().map(c -> Map.<String, Object>of(
                "number", c.number(), "role", c.role(), "name", c.name(), "value", c.value(),
                "states", c.states(), "ref", c.ref(), "locator", c.locator())).toList();
        return Map.of("url", controls.url(), "total", controls.all().size(), "controls", list);
    }

    private String controlsAsText(String filter) {
        PageControls controls = browser.controls();
        List<PageControls.Control> shown = controls.filtered(filter, 40);
        if (shown.isEmpty()) {
            return filter == null || filter.isBlank()
                    ? "This page has no buttons or input fields. Read the page or follow a link instead."
                    : "No control matches \"" + filter + "\". Call web_controls without filter to see all.";
        }
        StringBuilder out = new StringBuilder("Controls on ").append(controls.url()).append(":\n");
        shown.forEach(control -> out.append(control.line()).append('\n'));
        int hidden = controls.filtered(filter, Integer.MAX_VALUE).size() - shown.size();
        if (hidden > 0) out.append("(").append(hidden).append(" more; use a filter word)\n");
        out.append("Next: web_click <number> or web_fill <number> \"text\".");
        return out.toString();
    }

    private static Map<String, Object> interactionAsJson(CamoufoxBrowser.Interaction interaction) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("target", interaction.target());
        result.put("url", interaction.url());
        result.put("title", interaction.title());
        if (interaction.openedTab() != null) result.put("openedTab", interaction.openedTab());
        return result;
    }

    private static String interactionAsText(String verb, CamoufoxBrowser.Interaction interaction) {
        String text = verb + " " + interaction.target() + ". Page: " + interaction.title() + " (" + interaction.url() + ")";
        if (interaction.openedTab() != null) text += "\nThis opened a new tab, which is now shown.";
        return text;
    }

    /** Small models get the browser's own sentence, without exception class names. */
    private static String plain(java.util.function.Supplier<String> action) {
        try {
            return action.get();
        } catch (IllegalStateException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
                throw new IllegalArgumentException(cause.getMessage(), cause);
            }
            throw wrapped;
        }
    }
}
