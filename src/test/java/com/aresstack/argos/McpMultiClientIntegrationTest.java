package com.aresstack.argos;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * initialize, tools/list and tools/call over real Streamable HTTP with the MCP Java SDK client: a ChatGPT-like
 * client (with _meta["openai/session"]) and Claude-like clients (without it) each reach their own workspace.
 */
class McpMultiClientIntegrationTest {
    @TempDir Path root;

    private ChatRoutedTools.Workspace fake(Path profile) {
        return new ChatRoutedTools.Workspace(List.of(
                ArgosTools.textTool("whoami", "", Map.of(), List.of(), args -> profile.toString())),
                (id, cookies) -> { }, () -> { });
    }

    private static McpSyncClient client(int port, String name) {
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/").build())
                .clientInfo(new Implementation(name, "1.0")).build();
        client.initialize();
        return client;
    }

    private static String whoami(McpSyncClient client, Map<String, Object> meta) {
        CallToolResult result = client.callTool(new CallToolRequest("whoami", Map.of(), meta));
        String text = ((TextContent) result.content().get(0)).text();
        assertNotEquals(Boolean.TRUE, result.isError(), text);
        return text;
    }

    @Test
    void chatGptAndClaudeLikeClientsGetSeparateWorkspacesWithoutSingleMode() throws Exception {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, root.resolve("unused"), true,
                null, "none", null, null);
        try (ChatRoutedTools routed = new ChatRoutedTools(new ChatProfiles(root), this::fake, 4);
             ArgosApplication.RunningServer running = ArgosApplication.serve(config,
                     routed.specifications(fake(root.resolve("template")).tools(), true))) {
            McpSyncClient claudeA = client(running.port(), "claude-ai");
            McpSyncClient claudeB = client(running.port(), "claude-ai");
            McpSyncClient chatGpt = client(running.port(), "openai-mcp");
            try {
                assertTrue(claudeA.listTools().tools().stream().anyMatch(tool -> tool.name().equals("whoami")));
                String a = whoami(claudeA, null);
                assertEquals(a, whoami(claudeA, null), "one connection keeps its workspace");
                String b = whoami(claudeB, null);
                assertNotEquals(a, b, "two connections never share a workspace");
                assertTrue(Path.of(a).getParent().getFileName().toString().startsWith(ChatProfiles.TRANSPORT_PREFIX));

                String conversation = whoami(chatGpt, Map.of(ChatProfiles.SESSION_META_KEY, "conv-A"));
                assertEquals(root.resolve(ChatProfiles.hash("conv-A")).resolve("profile").toString(), conversation);
                assertNotEquals(a, conversation);
                assertNotEquals(whoami(chatGpt, null), conversation,
                        "without its conversation id the same connection is a transport workspace");
                assertEquals("* start (active)", ((TextContent) claudeB.callTool(
                        new CallToolRequest("browser_contexts", Map.of())).content().get(0)).text());
            } finally {
                claudeA.closeGracefully();
                claudeB.closeGracefully();
                chatGpt.closeGracefully();
            }
        }
    }
}
