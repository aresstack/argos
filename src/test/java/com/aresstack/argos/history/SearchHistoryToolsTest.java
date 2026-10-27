package com.aresstack.argos.history;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Without a history (single profile) the tools stay listed and explain why they are off. */
class SearchHistoryToolsTest {

    @Test void disabledToolsKeepTheirNamesAndAnswerWithTheReason() {
        List<SyncToolSpecification> tools = SearchHistoryTools.disabled("Search History is disabled: single").specifications();
        assertEquals(List.of("search_history", "search_history_export"), tools.stream().map(t -> t.tool().name()).toList());
        for (SyncToolSpecification tool : tools) {
            CallToolResult result = tool.callHandler().apply(null,
                    new CallToolRequest(tool.tool().name(), Map.of("query", "x", "format", "txt")));
            assertTrue(result.isError());
            assertEquals("Search History is disabled: single", ((TextContent) result.content().get(0)).text());
        }
    }
}
