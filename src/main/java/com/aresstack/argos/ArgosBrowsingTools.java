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
