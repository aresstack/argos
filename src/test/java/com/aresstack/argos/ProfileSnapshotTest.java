package com.aresstack.argos;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The convenience profile must leave the other profiles exactly as they were: tool names, descriptions and input
 * schemas of legacy, compact and expert are compared with snapshots taken on develop before the convenience
 * profile existed. Regenerate only on purpose: -Dargos.writeProfileSnapshots=&lt;directory&gt;.
 */
class ProfileSnapshotTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @Test void legacyCompactAndExpertAreUnchanged() throws Exception {
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null, temp, true))) {
            for (ToolProfile profile : List.of(ToolProfile.LEGACY, ToolProfile.COMPACT, ToolProfile.EXPERT)) {
                String actual = snapshot(new ArgosTools(browser).forProfile(profile));
                String file = "profile-" + profile.name().toLowerCase(java.util.Locale.ROOT) + ".json";
                String target = System.getProperty("argos.writeProfileSnapshots");
                if (target != null) {
                    Files.writeString(Path.of(target).resolve(file), actual);
                    continue;
                }
                try (InputStream in = ProfileSnapshotTest.class.getResourceAsStream("/profiles/" + file)) {
                    assertNotNull(in, file);
                    // As JSON trees: property order of the schemas is not stable between JVM runs (Map.of).
                    assertEquals(JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
                            JSON.readTree(actual), profile.name());
                }
            }
        }
    }

    static String snapshot(List<SyncToolSpecification> tools) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SyncToolSpecification spec : tools) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", spec.tool().name());
            tool.put("description", spec.tool().description());
            tool.put("inputSchema", spec.tool().inputSchema());
            out.add(tool);
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }
}
