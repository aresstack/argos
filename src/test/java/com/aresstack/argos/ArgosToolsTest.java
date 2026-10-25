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
            assertEquals(9, tools.size());
            assertTrue(names.contains("browser_navigate"));
            assertTrue(names.contains("browser_screenshot"));
            assertTrue(names.contains("browser_network"));
            assertEquals("browser_status", tools.get(0).tool().name());
            assertFalse(names.stream().anyMatch(name -> name.contains("chromium")));
            assertFalse(names.contains("browser_network_detail"));
            assertEquals(9, new ArgosTools(browser).forProfile(ToolProfile.LEGACY).size());
        }
    }

    @Test void publishesNetworkAnalysisOnlyInExpertProfile() {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, temp, true);
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config)) {
            Set<String> names = new ArgosTools(browser).forProfile(ToolProfile.EXPERT).stream()
                    .map(spec -> spec.tool().name()).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("browser_network", "browser_network_detail",
                    "browser_network_har", "browser_network_clear", "browser_network_json")));
            assertEquals(13, names.size());
        }
    }

    @Test void parsesToolProfile() {
        assertEquals(ToolProfile.LEGACY, ToolProfile.parse(null));
        assertEquals(ToolProfile.LEGACY, ToolProfile.parse(" "));
        assertEquals(ToolProfile.EXPERT, ToolProfile.parse("Expert"));
        assertThrows(IllegalArgumentException.class, () -> ToolProfile.parse("everything"));
        assertTrue(new ArgosTools(null).forProfile(ToolProfile.COMPACT).stream()
                .noneMatch(spec -> spec.tool().name().startsWith("browser_network_")));
    }
}
