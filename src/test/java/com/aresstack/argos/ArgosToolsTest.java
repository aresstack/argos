package com.aresstack.argos;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import com.aresstack.argos.research.ResearchBudget;
import com.aresstack.argos.research.ResearchSettings;
import com.aresstack.argos.web.SearchResults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class ArgosToolsTest {
    private static final List<String> COMPACT = List.of(
            "web_search", "web_open", "web_read", "web_find", "web_links", "web_follow", "web_back");

    @TempDir Path temp;

    @Test void registersOnlyCamoufoxResearchTools() {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, temp, true);
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config)) {
            var tools = new ArgosTools(browser).all();
            Set<String> names = names(tools);
            assertEquals(9, tools.size(), "the legacy profile stays exactly nine tools");
            assertTrue(names.contains("browser_navigate"));
            assertTrue(names.contains("browser_screenshot"));
            assertTrue(names.contains("browser_network"));
            assertFalse(names.contains("browser_wait"), "new tools go to the expert profile");
            assertEquals("browser_status", tools.get(0).tool().name());
            assertFalse(names.stream().anyMatch(name -> name.contains("chromium")));
            assertEquals(names, names(new ArgosTools(browser).forProfile(ToolProfile.LEGACY)));
            assertFalse(names.contains("browser_network_detail"));
            assertFalse(names.contains("web_research"), "research orchestration is opt-in");
        }
    }

    @Test void publishesResearchToolsOnlyWhenEnabled() {
        try (CamoufoxBrowser browser = browser()) {
            var enabled = new ResearchSettings(true, ResearchBudget.defaults());
            var web = new com.aresstack.argos.web.WebSession(browser, com.aresstack.argos.web.UrlPolicy.publicOnly(),
                    query -> new SearchResults(List.of(), List.of()));
            for (ToolProfile profile : List.of(ToolProfile.LEGACY, ToolProfile.COMPACT, ToolProfile.EXPERT)) {
                int plain = new ArgosTools(browser, web).forProfile(profile).size();
                var tools = new ArgosTools(browser, web, enabled).forProfile(profile);
                List<String> names = tools.stream().map(spec -> spec.tool().name()).toList();
                assertEquals(plain + 2, tools.size(), profile.name());
                assertEquals(List.of("web_research", "web_research_stop"), names.subList(plain, plain + 2));
                assertEquals(List.of("question"), tools.get(plain).tool().inputSchema().required());
            }
        }
    }

    @Test void compactProfilePublishesOnlyTheShortWebTools() {
        try (CamoufoxBrowser browser = browser()) {
            var tools = new ArgosTools(browser).forProfile(ToolProfile.COMPACT);
            assertEquals(COMPACT, tools.stream().map(spec -> spec.tool().name()).toList());
            for (SyncToolSpecification spec : tools) {
                var schema = spec.tool().inputSchema();
                assertTrue(schema.properties().size() <= 1, spec.tool().name() + " must stay simple");
                assertTrue(spec.tool().description().contains("Example:"), spec.tool().name());
                schema.properties().values().forEach(property ->
                        assertFalse(((java.util.Map<?, ?>) property).get("type").equals("object"),
                                "no nested parameters"));
            }
        }
    }

    @Test void expertProfileOffersWebAndBrowserTools() {
        try (CamoufoxBrowser browser = browser()) {
            Set<String> names = names(new ArgosTools(browser).forProfile(ToolProfile.EXPERT));
            assertTrue(names.containsAll(COMPACT));
            assertTrue(names.contains("browser_evaluate"));
            assertTrue(names.contains("browser_inspect"));
            assertTrue(names.containsAll(Set.of("browser_pages_navigate", "browser_pages_snapshot",
                    "browser_pages_inspect")));
        }
    }

    @Test void parallelPageToolsRejectDuplicateTabMutationsBeforeLaunchingBrowser() {
        try (CamoufoxBrowser browser = browser()) {
            SyncToolSpecification navigate = new ParallelPageTools(browser).expertTools().stream()
                    .filter(specification -> specification.tool().name().equals("browser_pages_navigate"))
                    .findFirst().orElseThrow();
            CallToolResult result = navigate.callHandler().apply(null, new CallToolRequest("browser_pages_navigate",
                    Map.of("pages", List.of(
                            Map.of("tab", "t1", "url", "https://example.org/"),
                            Map.of("tab", "1", "url", "https://example.net/")))));
            assertTrue(result.isError());
            String message = ((TextContent) result.content().get(0)).text();
            assertTrue(message.contains("A tab may appear only once"), message);
        }
    }

    @Test void compactToolsAnswerInPlainTextAndReportErrorsAsToolErrors() {
        try (CamoufoxBrowser browser = browser()) {
            var web = new com.aresstack.argos.web.WebSession(browser,
                    com.aresstack.argos.web.UrlPolicy.publicOnly(), null);
            var tools = new ArgosTools(browser, web).forProfile(ToolProfile.COMPACT);
            var follow = tools.stream().filter(spec -> spec.tool().name().equals("web_follow")).findFirst().orElseThrow();
            var result = follow.callHandler().apply(null, new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                    "web_follow", java.util.Map.of("link_id", "link-9")));
            assertTrue(result.isError());
            String message = ((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0)).text();
            assertTrue(message.contains("call web_links first"), message);
        }
    }

    private CamoufoxBrowser browser() {
        return new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null, temp, true));
    }

    private static Set<String> names(List<SyncToolSpecification> tools) {
        return tools.stream().map(spec -> spec.tool().name()).collect(Collectors.toSet());
    }

    @Test void browsingToolsAreSeparateListsForTheProfiles() {
        try (CamoufoxBrowser browser = browser()) {
            ArgosBrowsingTools browsing = new ArgosBrowsingTools(browser);
            Set<String> expert = browsing.expert().stream().map(spec -> spec.tool().name()).collect(Collectors.toSet());
            Set<String> compact = browsing.compact().stream().map(spec -> spec.tool().name()).collect(Collectors.toSet());
            assertEquals(Set.of("browser_tabs", "browser_tab_new", "browser_tab_select", "browser_tab_close",
                    "browser_back", "browser_forward", "browser_reload", "browser_controls",
                    "browser_click_target", "browser_fill_target", "browser_press_key", "browser_extract", "browser_mouse_move",
                    "browser_mouse_click", "browser_hover", "browser_scroll", "browser_drag"), expert);
            assertEquals(Set.of("web_controls", "web_click", "web_fill"), compact);
            for (var spec : browsing.compact()) {
                assertTrue(spec.tool().inputSchema().properties().size() <= 2, spec.tool().name());
            }
            Set<String> published = names(new ArgosTools(browser).forProfile(ToolProfile.EXPERT));
            assertTrue(published.containsAll(expert), "expert profile publishes the browsing tools");
            assertTrue(java.util.Collections.disjoint(compact,
                    names(new ArgosTools(browser).forProfile(ToolProfile.COMPACT))),
                    "compact interaction tools wait for the small-model test");
        }
    }

    @Test void pointerToolsDeclareViewportCoordinatesAndTheirParameters() {
        try (CamoufoxBrowser browser = browser()) {
            Map<String, SyncToolSpecification> tools = new ArgosBrowsingTools(browser).expert().stream()
                    .collect(Collectors.toMap(spec -> spec.tool().name(), spec -> spec));
            assertEquals(Set.of("x", "y", "tab"), tools.get("browser_mouse_move").tool().inputSchema().properties().keySet());
            assertEquals(List.of("x", "y"), tools.get("browser_mouse_move").tool().inputSchema().required());
            assertEquals(Set.of("x", "y", "button", "tab"),
                    tools.get("browser_mouse_click").tool().inputSchema().properties().keySet());
            assertEquals(List.of("x", "y"), tools.get("browser_mouse_click").tool().inputSchema().required());
            assertEquals(List.of("target"), tools.get("browser_hover").tool().inputSchema().required());
            assertEquals(Set.of("delta_x", "delta_y", "target", "x", "y", "tab"),
                    tools.get("browser_scroll").tool().inputSchema().properties().keySet());
            assertEquals(List.of(), tools.get("browser_scroll").tool().inputSchema().required());
            for (String name : List.of("browser_mouse_move", "browser_mouse_click", "browser_scroll")) {
                String description = tools.get(name).tool().description();
                assertTrue(description.contains("CSS pixels") && description.contains("viewport"), name);
                assertEquals("number", ((Map<?, ?>) tools.get(name).tool().inputSchema().properties().get("x"))
                        .get("type"), name);
            }
            assertTrue(tools.get("browser_hover").tool().description().contains("without clicking"));
        }
    }

    @Test void pointerToolsRejectInvalidInputBeforeTouchingTheBrowser() {
        try (CamoufoxBrowser browser = browser()) {
            List<SyncToolSpecification> tools = new ArgosBrowsingTools(browser).expert();
            assertError(tools, "browser_mouse_move", Map.of("x", 10), "Missing number field: y");
            assertError(tools, "browser_mouse_move", Map.of("x", "left", "y", 5), "Missing number field: x");
            assertError(tools, "browser_mouse_click", Map.of("x", 1, "y", 1, "button", "back"),
                    "button must be left, middle or right");
            assertError(tools, "browser_hover", Map.of("target", " "), "target is required");
            assertError(tools, "browser_scroll", Map.of(), "delta_y");
            assertError(tools, "browser_scroll", Map.of("delta_y", 100, "x", 5), "both x and y");
            assertError(tools, "browser_scroll", Map.of("delta_y", 100, "x", 5, "y", 5, "target", "Suchen"),
                    "either target or x/y");
            assertError(tools, "browser_scroll", Map.of("delta_y", 1e6), "at most 20000");
            assertEquals(false, browser.status().get("started"), "validation must not launch the browser");
        }
    }

    @Test void pointerToolsReportAMissingBrowserSessionAsToolError() {
        try (CamoufoxBrowser browser = browser()) {
            CallToolResult result = call(new ArgosBrowsingTools(browser).expert(), "browser_mouse_move",
                    Map.of("x", 10, "y", 10));
            assertTrue(result.isError());
            // No browser configured and no download allowed in unit tests: the installation error is the tool result.
            assertTrue(((TextContent) result.content().get(0)).text().contains("Camoufox"));
        }
    }

    private static void assertError(List<SyncToolSpecification> tools, String name, Map<String, Object> arguments,
                                    String expected) {
        CallToolResult result = call(tools, name, arguments);
        assertTrue(result.isError(), name + " " + arguments);
        String message = ((TextContent) result.content().get(0)).text();
        assertTrue(message.contains(expected), message);
    }

    private static CallToolResult call(List<SyncToolSpecification> tools, String name, Map<String, Object> arguments) {
        return tools.stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow()
                .callHandler().apply(null, new CallToolRequest(name, arguments));
    }

    @Test void publishesNetworkAnalysisOnlyInExpertProfile() {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, temp, true);
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config)) {
            Set<String> names = new ArgosTools(browser).forProfile(ToolProfile.EXPERT).stream()
                    .map(spec -> spec.tool().name()).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("browser_network", "browser_network_detail",
                    "browser_network_har", "browser_network_clear", "browser_network_json")));
        }
    }

    @Test void parsesToolProfile() {
        assertEquals(ToolProfile.DEVELOPER, ToolProfile.parse(null));
        assertEquals(ToolProfile.DEVELOPER, ToolProfile.parse(" "));
        assertEquals(ToolProfile.EXPERT, ToolProfile.parse("Expert"));
        assertThrows(IllegalArgumentException.class, () -> ToolProfile.parse("everything"));
        assertTrue(new ArgosTools(null).forProfile(ToolProfile.COMPACT).stream()
                .noneMatch(spec -> spec.tool().name().startsWith("browser_network_")));
    }
}
