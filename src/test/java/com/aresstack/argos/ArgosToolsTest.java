package com.aresstack.argos;

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
    @TempDir Path temp;
    @Test void registersOnlyCamoufoxResearchTools() {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, temp, true);
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config)) {
            var tools = new ArgosTools(browser).all();
            Set<String> names = tools.stream().map(spec -> spec.tool().name()).collect(Collectors.toSet());
            assertEquals(10, tools.size());
            assertTrue(names.contains("web_search"));
            assertTrue(names.contains("browser_navigate"));
            assertTrue(names.contains("browser_screenshot"));
            assertTrue(names.contains("browser_network"));
            assertEquals("browser_status", tools.get(0).tool().name());
            assertFalse(names.stream().anyMatch(name -> name.contains("chromium")));
            assertFalse(names.contains("web_research"), "research orchestration is opt-in");
        }
    }

    @Test void publishesResearchToolsOnlyWhenEnabled() {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, temp, true);
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config)) {
            var enabled = new ResearchSettings(true, ResearchBudget.defaults());
            var tools = new ArgosTools(browser, query -> new SearchResults(List.of(), List.of()), enabled).all();
            List<String> names = tools.stream().map(spec -> spec.tool().name()).toList();
            assertEquals(12, tools.size());
            assertEquals(List.of("web_research", "web_research_stop"), names.subList(10, 12));
            assertEquals(List.of("question"), tools.get(10).tool().inputSchema().required());
        }
    }
}
