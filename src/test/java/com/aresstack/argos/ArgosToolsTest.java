package com.aresstack.argos;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import com.aresstack.argos.research.ResearchBudget;
import com.aresstack.argos.research.ResearchSettings;
import com.aresstack.argos.web.SearchResults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class ArgosToolsTest {
    private static final List<String> COMPACT = List.of(
            "web_search", "web_open", "web_read", "web_links", "web_follow", "web_back");

    @TempDir Path temp;

    @Test void registersOnlyCamoufoxResearchTools() {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, temp, true);
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config)) {
            var tools = new ArgosTools(browser).all();
            Set<String> names = names(tools);
            assertEquals(9, tools.size());
            assertTrue(names.contains("browser_navigate"));
            assertTrue(names.contains("browser_screenshot"));
            assertTrue(names.contains("browser_network"));
            assertEquals("browser_status", tools.get(0).tool().name());
            assertFalse(names.stream().anyMatch(name -> name.contains("chromium")));
            assertEquals(names, names(new ArgosTools(browser).forProfile(ToolProfile.LEGACY)));
            assertFalse(names.contains("web_research"), "research orchestration is opt-in");
        }
    }

    @Test void publishesResearchToolsOnlyWhenEnabled() {
        try (CamoufoxBrowser browser = browser()) {
            var enabled = new ResearchSettings(true, ResearchBudget.defaults());
            var web = new com.aresstack.argos.web.WebSession(browser, new com.aresstack.argos.web.UrlPolicy(false),
                    query -> new SearchResults(List.of(), List.of()));
            for (ToolProfile profile : ToolProfile.values()) {
                int plain = new ArgosTools(browser, web).forProfile(profile).size();
                var tools = new ArgosTools(browser, web, enabled).forProfile(profile);
                List<String> names = tools.stream().map(spec -> spec.tool().name()).toList();
                assertEquals(plain + 2, tools.size(), profile.name());
                assertEquals(List.of("web_research", "web_research_stop"), names.subList(plain, plain + 2));
                assertEquals(List.of("question"), tools.get(plain).tool().inputSchema().required());
            }
        }
    }

    @Test void compactProfilePublishesOnlyTheSixShortAskAiTools() {
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
        }
    }

    @Test void compactToolsAnswerInPlainTextAndReportErrorsAsToolErrors() {
        try (CamoufoxBrowser browser = browser()) {
            var web = new com.aresstack.argos.web.WebSession(browser,
                    new com.aresstack.argos.web.UrlPolicy(false), null);
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
}
