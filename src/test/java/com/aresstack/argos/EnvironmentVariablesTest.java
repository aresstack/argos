package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentVariablesTest {
    @Test
    void newNamesPassThroughWithoutWarnings() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> environment = EnvironmentVariables.canonical(
                Map.of("TOOL_PROFILE", "expert", "SERVER_PORT", "8085"), warnings::add);
        assertEquals("expert", environment.get("TOOL_PROFILE"));
        assertEquals("8085", environment.get("SERVER_PORT"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void deprecatedAliasFillsInTheNewNameAndIsReportedWithoutItsValue() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> environment = EnvironmentVariables.canonical(Map.of(
                "ARGOS_PROFILE_ISOLATION", "single",
                "ARGOS_UPSTREAM_PROXY", "socks5://user:secret@proxy:1080"), warnings::add);
        assertEquals("single", environment.get("PROFILE_ISOLATION"));
        assertEquals("socks5://user:secret@proxy:1080", environment.get("UPSTREAM_PROXY"));
        assertNull(environment.get("ARGOS_PROFILE_ISOLATION"), "the alias is consumed");
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ARGOS_UPSTREAM_PROXY") && w.contains("UPSTREAM_PROXY")));
        assertFalse(String.join("\n", warnings).contains("secret"), "values never reach the log");
    }

    @Test
    void renamedAliasesMapToTheirDescriptiveNames() {
        Map<String, String> environment = EnvironmentVariables.canonical(Map.of(
                "ARGOS_PROFILE_DIR", "/profiles/default", "ARGOS_HEADLESS", "true",
                "ARGOS_RESEARCH", "true", "ARGOS_SEARCH_HISTORY", "false"));
        assertEquals("/profiles/default", environment.get("BROWSER_PROFILE_DIR"));
        assertEquals("true", environment.get("BROWSER_HEADLESS"));
        assertEquals("true", environment.get("RESEARCH_ENABLED"));
        assertEquals("false", environment.get("SEARCH_HISTORY_ENABLED"));
        assertNull(environment.get("PROFILE_DIR"));
        assertNull(environment.get("HEADLESS"));
    }

    @Test
    void newNameWinsOverAConflictingAlias() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> environment = EnvironmentVariables.canonical(Map.of(
                "MAX_BROWSERS", "4", "ARGOS_MAX_BROWSERS", "2",
                "SEARCH_ENGINES", "bing", "ARGOS_SEARCH_ENGINES", "bing"), warnings::add);
        assertEquals("4", environment.get("MAX_BROWSERS"));
        assertEquals("bing", environment.get("SEARCH_ENGINES"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ARGOS_MAX_BROWSERS") && w.contains("different values")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ARGOS_SEARCH_ENGINES") && w.contains("duplicates")),
                warnings.toString());
    }

    @Test
    void blankNewNameDoesNotHideTheAlias() {
        assertEquals("expert", EnvironmentVariables.canonical(
                Map.of("TOOL_PROFILE", " ", "ARGOS_TOOL_PROFILE", "expert")).get("TOOL_PROFILE"));
    }

    @Test
    void retiredNamesAndTheSelfTestMarkerAreNoAliases() {
        Map<String, String> environment = EnvironmentVariables.canonical(Map.of(
                "ARGOS_ALLOW_PRIVATE_NETWORKS", "true", "ARGOS_SEARCH_HISTORY_HTML", "true",
                "ARGOS_BROWSER_DIR", "/legacy", "ARGOS_RUNTIME_OK", "1"));
        assertEquals("true", environment.get("ARGOS_ALLOW_PRIVATE_NETWORKS"));
        assertEquals("true", environment.get("ARGOS_SEARCH_HISTORY_HTML"));
        assertNull(environment.get("BROWSER_DIR"), "#56 renamed BROWSER_DIR without an alias");
        assertNull(environment.get("RUNTIME_OK"));
        assertNull(environment.get("ALLOW_PRIVATE_NETWORKS"));
    }

    @Test
    void everyNewNameHasExactlyOneAlias() {
        assertEquals(EnvironmentVariables.NAMES.size(), EnvironmentVariables.LEGACY_ALIASES.size());
        assertTrue(EnvironmentVariables.LEGACY_ALIASES.values().containsAll(EnvironmentVariables.NAMES));
        EnvironmentVariables.NAMES.forEach(name -> assertFalse(name.startsWith("ARGOS_"), name));
    }
}
