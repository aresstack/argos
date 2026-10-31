package com.aresstack.argos;

import com.aresstack.argos.convenience.ConvenienceSession;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The nine tools of the {@link ToolProfile#CONVENIENCE} profile: parallel search over all engines, pages opened by
 * result and link ids in new tabs, every page answer with its text and links together. Answers are JSON; URLs only
 * through web_get_url.
 */
public final class ConvenienceTools {
    /** MCP server instructions of the convenience profile. */
    public static final String INSTRUCTIONS = "Every page response includes its available links with IDs. "
            + "Navigate using those IDs. Never reconstruct, modify, or pass link URLs. "
            + "Use URL lookup only when an actual URL is explicitly needed.";

    static final List<String> NAMES = List.of("web_search", "web_select_results", "web_select_links",
            "web_follow_link", "web_read_tab", "web_back", "web_open_url", "web_get_url", "web_close_tabs");

    private final ConvenienceSession session;

    public ConvenienceTools(ConvenienceSession session) {
        this.session = session;
    }

    ConvenienceSession session() {
        return session;
    }

    public List<SyncToolSpecification> specifications() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        Map<String, Object> engineId = new LinkedHashMap<>(ArgosTools.field("string", "Search engine id"));
        if (!session.engineIds().isEmpty()) engineId.put("enum", session.engineIds());
        tools.add(ArgosTools.tool("web_search",
                "Search the configured search engines in parallel. Returns combined results with IDs, titles and "
                        + "snippets. Select relevant result IDs to inspect them. Optional engines limits this one "
                        + "search to the named engines, e.g. [\"google\", \"bing\"].",
                Map.of("query", ArgosTools.field("string", "What to search for"),
                        "engines", array(engineId, "Only these engines, for this search only. Leave out to use the "
                                + "default engines.")),
                List.of("query"),
                args -> session.search(ArgosTools.string(args, "query"), optionalStrings(args, "engines"))));
        tools.add(ArgosTools.tool("web_select_results",
                "Select the search results you want to inspect. Argos opens the selected pages in new tabs and "
                        + "returns their text and links.",
                Map.of("searchId", ArgosTools.field("string", "searchId of the web_search answer, e.g. s1"),
                        "resultIds", array(ArgosTools.field("string", "resultId, e.g. r-s1-3"),
                                "Result ids to open, at most " + ConvenienceSession.MAX_BATCH)),
                List.of("searchId", "resultIds"),
                args -> session.selectResults(ArgosTools.string(args, "searchId"), strings(args, "resultIds"))));
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "object");
        choice.put("properties", Map.of(
                "tabId", ArgosTools.field("string", "Tab whose page lists the link, e.g. t21"),
                "linkId", ArgosTools.field("string", "linkId from that page, e.g. l-t21-4")));
        choice.put("required", List.of("tabId", "linkId"));
        tools.add(ArgosTools.tool("web_select_links",
                "Select links by tabId and linkId. Argos opens them in new tabs and returns their text and links.",
                Map.of("links", array(choice, "Links to open, at most " + ConvenienceSession.MAX_BATCH)),
                List.of("links"),
                args -> session.selectLinks(choices(args))));
        tools.add(ArgosTools.tool("web_follow_link",
                "Open a link by linkId in the specified tab. The response includes the new page text and links.",
                Map.of("tabId", ArgosTools.field("string", "Tab id, e.g. t21"),
                        "linkId", ArgosTools.field("string", "linkId from that tab's page, e.g. l-t21-4")),
                List.of("tabId", "linkId"),
                args -> session.followLink(ArgosTools.string(args, "tabId"), ArgosTools.string(args, "linkId"))));
        tools.add(ArgosTools.tool("web_read_tab",
                "Read the specified tab. Returns page text and links together.",
                Map.of("tabId", ArgosTools.field("string", "Tab id, e.g. t21"),
                        "part", ArgosTools.field("integer", "Text part, default 1")),
                List.of("tabId"),
                args -> session.readTab(ArgosTools.string(args, "tabId"), ArgosTools.number(args, "part", 1))));
        tools.add(ArgosTools.tool("web_back",
                "Go back in the specified tab. Returns page text and links.",
                Map.of("tabId", ArgosTools.field("string", "Tab id, e.g. t21")), List.of("tabId"),
                args -> session.back(ArgosTools.string(args, "tabId"))));
        tools.add(ArgosTools.tool("web_open_url",
                "Open an explicitly user-provided URL in a new tab. For search results and page links, use their "
                        + "IDs instead.",
                Map.of("url", ArgosTools.field("string", "The full http(s) URL the user gave")), List.of("url"),
                args -> session.openUrl(ArgosTools.string(args, "url"))));
        tools.add(ArgosTools.tool("web_get_url",
                "Return the exact stored URL for a known reference. Use only when the user asks for the URL or "
                        + "another tool requires it. Never use for normal navigation.",
                Map.of("referenceId", ArgosTools.field("string", "resultId (r-s1-3) or linkId (l-t21-4)")),
                List.of("referenceId"),
                args -> {
                    String id = ArgosTools.string(args, "referenceId");
                    Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("referenceId", id.trim());
                    answer.put("url", session.url(id));
                    return answer;
                }));
        tools.add(ArgosTools.tool("web_close_tabs",
                "Close the specified tabs. Other tabs remain open.",
                Map.of("tabIds", array(ArgosTools.field("string", "Tab id, e.g. t21"), "Tabs to close")),
                List.of("tabIds"),
                args -> session.closeTabs(strings(args, "tabIds"))));
        return List.copyOf(tools);
    }

    private static Map<String, Object> array(Map<String, Object> items, String description) {
        Map<String, Object> array = new LinkedHashMap<>();
        array.put("type", "array");
        array.put("items", items);
        array.put("description", description);
        return array;
    }

    /** A string list; models sometimes send one id or a comma separated string instead of an array. */
    static List<String> strings(Map<String, Object> args, String name) {
        Object value = args.get(name);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) if (item != null) out.add(String.valueOf(item).trim());
        } else if (value instanceof String text) {
            for (String part : text.split(",")) if (!part.isBlank()) out.add(part.trim());
        } else {
            throw new IllegalArgumentException("Missing list field: " + name);
        }
        return out;
    }

    /** Like {@link #strings}, but null when the field is absent: "not given" differs from an empty list. */
    static List<String> optionalStrings(Map<String, Object> args, String name) {
        return args.get(name) == null ? null : strings(args, name);
    }

    private static List<ConvenienceSession.LinkChoice> choices(Map<String, Object> args) {
        if (!(args.get("links") instanceof List<?> list)) {
            throw new IllegalArgumentException("Missing list field: links ([{\"tabId\": \"t21\", \"linkId\": \"l-t21-4\"}])");
        }
        List<ConvenienceSession.LinkChoice> choices = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("tabId") instanceof String tab)
                    || !(map.get("linkId") instanceof String link)) {
                throw new IllegalArgumentException("Each entry of links needs tabId and linkId as strings.");
            }
            choices.add(new ConvenienceSession.LinkChoice(tab, link));
        }
        return choices;
    }
}
