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
            assertEquals(23, tools.size());
            assertTrue(names.containsAll(Set.of("browser_tabs", "browser_back", "browser_extract",
                    "browser_controls", "web_controls", "web_click", "web_fill")));
            assertTrue(names.contains("browser_navigate"));
            assertTrue(names.contains("browser_screenshot"));
            assertTrue(names.contains("browser_network"));
            assertEquals("browser_status", tools.get(0).tool().name());
            assertFalse(names.stream().anyMatch(name -> name.contains("chromium")));
        }
    }
}
