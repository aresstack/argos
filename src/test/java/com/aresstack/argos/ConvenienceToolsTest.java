package com.aresstack.argos;

import com.aresstack.argos.convenience.ConvenienceSession;
import com.aresstack.argos.convenience.NavigationGate;
import com.aresstack.argos.research.ResearchBudget;
import com.aresstack.argos.research.ResearchSettings;
import com.aresstack.argos.web.SearchResults;
import com.aresstack.argos.web.UrlPolicy;
import com.aresstack.argos.web.WebSession;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConvenienceToolsTest {
    @TempDir Path temp;

    @Test void convenienceProfilePublishesExactlyItsNineToolsEvenWithResearchEnabled() {
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null, temp, true))) {
            WebSession web = new WebSession(browser, UrlPolicy.publicOnly(), query -> new SearchResults(List.of(), List.of()));
            ArgosTools tools = new ArgosTools(browser, web, new ResearchSettings(true, ResearchBudget.defaults()))
                    .withConvenience(new ConvenienceTools(session(browser)));

            List<String> names = tools.forProfile(ToolProfile.CONVENIENCE).stream().map(spec -> spec.tool().name()).toList();

            assertEquals(List.of("web_search", "web_select_results", "web_select_links", "web_follow_link",
                    "web_read_tab", "web_back", "web_open_url", "web_get_url", "web_close_tabs", "web_screenshot"), names);
            assertThrows(IllegalStateException.class, () -> new ArgosTools(browser, web).forProfile(ToolProfile.CONVENIENCE));
        }
    }

    @Test void developerProfileIsConveniencePlusDiagnosisOnTheSameTabIds() {
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null, temp, true))) {
            WebSession web = new WebSession(browser, UrlPolicy.publicOnly(), query -> new SearchResults(List.of(), List.of()));
            ArgosTools tools = new ArgosTools(browser, web).withConvenience(new ConvenienceTools(session(browser)));
            List<SyncToolSpecification> developer = tools.forProfile(ToolProfile.DEVELOPER);
            List<String> names = developer.stream().map(spec -> spec.tool().name()).toList();
            List<String> expected = new java.util.ArrayList<>(tools.forProfile(ToolProfile.CONVENIENCE).stream()
                    .map(spec -> spec.tool().name()).toList());
            expected.addAll(TabDiagnosticTools.DEVELOPER_NAMES);
            assertEquals(expected, names);

            SyncToolSpecification evaluate = developer.stream().filter(spec -> spec.tool().name().equals("web_evaluate"))
                    .findFirst().orElseThrow();
            assertEquals(List.of("tabId", "expression"), evaluate.tool().inputSchema().required());
            CallToolResult foreign = evaluate.callHandler().apply(null, new CallToolRequest("web_evaluate",
                    Map.of("tabId", "t1", "expression", "() => 1")));
            assertTrue(foreign.isError());
            assertTrue(((TextContent) foreign.content().get(0)).text().contains("Unknown tab"));
        }
    }

    @Test void descriptionsAndSchemasFollowTheSpecification() {
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null, temp, true))) {
            Map<String, SyncToolSpecification> tools = new java.util.HashMap<>();
            new ConvenienceTools(session(browser)).specifications().forEach(spec -> tools.put(spec.tool().name(), spec));

            assertEquals("Search the configured search engines in parallel. Returns combined results with IDs, titles "
                    + "and snippets. Select relevant result IDs to inspect them. Optional engines limits this one "
                    + "search to the named engines, e.g. [\"google\", \"bing\"].", tools.get("web_search").tool().description());
            assertEquals(List.of("query"), tools.get("web_search").tool().inputSchema().required());
            assertEquals("Return the exact stored URL for a known reference. Use only when the user asks for the URL or "
                    + "another tool requires it. Never use for normal navigation.", tools.get("web_get_url").tool().description());
            assertEquals("Close the specified tabs. Other tabs remain open.", tools.get("web_close_tabs").tool().description());
            assertEquals(List.of("searchId", "resultIds"), tools.get("web_select_results").tool().inputSchema().required());
            assertEquals(List.of("tabId"), tools.get("web_read_tab").tool().inputSchema().required());
            assertEquals("array", ((Map<?, ?>) tools.get("web_select_links").tool().inputSchema().properties().get("links")).get("type"));
            assertTrue(ConvenienceTools.INSTRUCTIONS.startsWith("Every page response includes its available links with IDs."));

            CallToolResult unknown = tools.get("web_get_url").callHandler().apply(null,
                    new CallToolRequest("web_get_url", Map.of("referenceId", "r-s1-1")));
            assertTrue(unknown.isError());
            assertTrue(((TextContent) unknown.content().get(0)).text().contains("Unknown or expired"));
        }
    }

    @Test void webSearchTakesAnOptionalEngineSelectionForThisCallOnly() {
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null, temp, true))) {
            List<Object> calls = new java.util.ArrayList<>();
            ConvenienceSession.Search search = new ConvenienceSession.Search() {
                @Override
                public List<com.aresstack.argos.search.ParallelWebSearch.EngineRun> run(String query, List<String> engines) {
                    calls.add(engines == null ? "default" : engines);
                    return List.of();
                }

                @Override
                public List<String> engineIds() {
                    return List.of("duckduckgo", "bing", "google");
                }

                @Override
                public void checkEngines(List<String> engines) {
                    if (engines != null && engines.isEmpty()) throw new IllegalArgumentException("engines is empty");
                }
            };
            ConvenienceSession session = new ConvenienceSession(browser, NavigationGate.OPEN, UrlPolicy.publicOnly(),
                    search, null);
            SyncToolSpecification webSearch = new ConvenienceTools(session).specifications().stream()
                    .filter(spec -> spec.tool().name().equals("web_search")).findFirst().orElseThrow();

            Map<?, ?> engines = (Map<?, ?>) webSearch.tool().inputSchema().properties().get("engines");
            assertEquals("array", engines.get("type"));
            assertEquals(List.of("duckduckgo", "bing", "google"), ((Map<?, ?>) engines.get("items")).get("enum"));

            assertFalse(call(webSearch, Map.of("query", "JavaParser")).isError());
            assertFalse(call(webSearch, Map.of("query", "JavaParser", "engines", List.of("google", "bing"))).isError());
            assertFalse(call(webSearch, Map.of("query", "JavaParser", "engines", "bing")).isError());
            CallToolResult empty = call(webSearch, Map.of("query", "JavaParser", "engines", List.of()));
            assertTrue(empty.isError());
            assertTrue(((TextContent) empty.content().get(0)).text().contains("engines is empty"));
            assertEquals(List.of("default", List.of("google", "bing"), List.of("bing")), calls);
        }
    }

    private static CallToolResult call(SyncToolSpecification tool, Map<String, Object> args) {
        return tool.callHandler().apply(null, new CallToolRequest(tool.tool().name(), args));
    }

    private static ConvenienceSession session(CamoufoxBrowser browser) {
        return new ConvenienceSession(browser, NavigationGate.OPEN, UrlPolicy.publicOnly(), (query, engines) -> List.of(), null);
    }
}
