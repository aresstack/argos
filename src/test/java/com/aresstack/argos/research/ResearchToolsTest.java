package com.aresstack.argos.research;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.aresstack.argos.research.ResearchWorld.hit;
import static org.junit.jupiter.api.Assertions.*;

class ResearchToolsTest {
    private final ResearchWorld world = new ResearchWorld();
    private final ResearchTools tools = new ResearchTools(world.service(ResearchBudget.defaults()));

    @Test
    void publishesTwoSmallTools() {
        var specs = tools.specifications();
        assertEquals("web_research", specs.get(0).tool().name());
        assertTrue(specs.get(0).tool().description().contains("Example: {\"question\""));
        assertEquals(1, specs.get(0).tool().inputSchema().properties().size());
        assertEquals("web_research_stop", specs.get(1).tool().name());
        assertTrue(specs.get(1).tool().inputSchema().properties().isEmpty());
    }

    @Test
    void answersWithTheCompactReport() {
        world.hits("camoufox license", hit("Camoufox license", "https://a.example/l", "MPL"))
                .page("https://a.example/l", "Camoufox license", "Camoufox uses the MPL license.");
        CallToolResult result = call(0, Map.of("question", "camoufox license"));
        assertFalse(result.isError());
        assertTrue(text(result).startsWith("Research: camoufox license\nStopped: NO_RELEVANT_PATHS (1 sources"));
    }

    @Test
    void rejectsAMissingQuestionAndReportsAnIdleStop() {
        assertTrue(call(0, Map.of()).isError());
        assertEquals("No research is running.", text(call(1, Map.of())));
    }

    private CallToolResult call(int index, Map<String, Object> arguments) {
        return tools.specifications().get(index).callHandler()
                .apply(null, new CallToolRequest(index == 0 ? "web_research" : "web_research_stop", arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
