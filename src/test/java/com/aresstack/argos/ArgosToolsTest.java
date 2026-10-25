package com.aresstack.argos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
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
            assertEquals(9, tools.size(), "the legacy profile stays exactly nine tools");
            assertTrue(names.contains("browser_navigate"));
            assertTrue(names.contains("browser_screenshot"));
            assertTrue(names.contains("browser_network"));
            assertEquals("browser_status", tools.get(0).tool().name());
            assertFalse(names.stream().anyMatch(name -> name.contains("chromium")));
        }
    }

    @Test void browsingToolsAreSeparateListsForTheProfiles() {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, temp, true);
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config)) {
            ArgosBrowsingTools browsing = new ArgosBrowsingTools(browser);
            Set<String> expert = browsing.expert().stream().map(spec -> spec.tool().name()).collect(Collectors.toSet());
            Set<String> compact = browsing.compact().stream().map(spec -> spec.tool().name()).collect(Collectors.toSet());
            assertEquals(Set.of("browser_tabs", "browser_tab_new", "browser_tab_select", "browser_tab_close",
                    "browser_back", "browser_forward", "browser_reload", "browser_controls",
                    "browser_click_target", "browser_fill_target", "browser_extract"), expert);
            assertEquals(Set.of("web_controls", "web_click", "web_fill"), compact);
            for (var spec : browsing.compact()) {
                assertTrue(spec.tool().inputSchema().properties().size() <= 2, spec.tool().name());
            }
        }
    }
}
