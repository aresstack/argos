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
                    "web_read_tab", "web_back", "web_open_url", "web_get_url", "web_close_tabs"), names);
            assertThrows(IllegalStateException.class, () -> new ArgosTools(browser, web).forProfile(ToolProfile.CONVENIENCE));
        }
    }

    @Test void descriptionsAndSchemasFollowTheSpecification() {
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null, temp, true))) {
            Map<String, SyncToolSpecification> tools = new java.util.HashMap<>();
            new ConvenienceTools(session(browser)).specifications().forEach(spec -> tools.put(spec.tool().name(), spec));

            assertEquals("Search all configured search engines in parallel. Returns combined results with IDs, titles "
                    + "and snippets. Select relevant result IDs to inspect them.", tools.get("web_search").tool().description());
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

    private static ConvenienceSession session(CamoufoxBrowser browser) {
        return new ConvenienceSession(browser, NavigationGate.OPEN, UrlPolicy.publicOnly(), query -> List.of(), null);
    }
}
