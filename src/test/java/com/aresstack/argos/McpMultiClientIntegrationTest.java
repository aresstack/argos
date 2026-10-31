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
        return client(port, name, null);
    }

    private static McpSyncClient client(int port, String name, String token) {
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/").customizeRequest(request -> {
                            if (token != null) request.header("Authorization", "Bearer " + token);
                        }).build())
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

    /** A local issuer: JWKS over HTTP, tokens signed like AnAuthentia's (sub = client id). */
    private static final class Issuer implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final String url;
        final com.aresstack.anauthentia.jwt.JwtAccessTokenIssuer tokens;

        Issuer(com.aresstack.anauthentia.jwt.JwtKeyRing keys) throws java.io.IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            url = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] jwks = keys.publicJwksJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.start();
            tokens = new com.aresstack.anauthentia.jwt.JwtAccessTokenIssuer(url, url + "/", keys);
        }

        String token(String clientId, String scope) {
            java.time.Instant now = java.time.Instant.now();
            return tokens.generate(clientId, scope, now, now.plusSeconds(600));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void signedTokensBindWorkspacesToTheirPrincipalEndToEnd() throws Exception {
        try (Issuer issuer = new Issuer(com.aresstack.anauthentia.jwt.JwtKeyRing.generate("k1"));
             Issuer forger = new Issuer(com.aresstack.anauthentia.jwt.JwtKeyRing.generate("k1"))) {
            ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, root.resolve("unused"), true,
                    null, "central-oauth", issuer.url, "argos");
            try (ChatRoutedTools routed = new ChatRoutedTools(new ChatProfiles(root), this::fake, 4);
                 ArgosApplication.RunningServer running = ArgosApplication.serve(config,
                         routed.specifications(fake(root.resolve("template")).tools(), true))) {
                String angel = issuer.token("angel", "argos");
                McpSyncClient claude = client(running.port(), "claude-ai", angel);
                McpSyncClient chatGpt = client(running.port(), "openai-mcp", angel);
                McpSyncClient other = client(running.port(), "claude-ai", issuer.token("other-client", "argos"));
                try {
                    String claudeWorkspace = whoami(claude, null);
                    assertTrue(Path.of(claudeWorkspace).getParent().getFileName().toString()
                            .startsWith(ChatProfiles.TRANSPORT_PREFIX));
                    String conversation = whoami(chatGpt, Map.of(ChatProfiles.SESSION_META_KEY, "conv-A"));
                    assertNotEquals(claudeWorkspace, conversation, "same OAuth client, still separate workspaces");

                    // Another verified principal that sends the same conversation id is refused.
                    CallToolResult foreign = other.callTool(new CallToolRequest("whoami", Map.of(),
                            Map.of(ChatProfiles.SESSION_META_KEY, "conv-A")));
                    assertEquals(Boolean.TRUE, foreign.isError());
                    assertTrue(((TextContent) foreign.content().get(0)).text().contains("another signed-in account"));
                    assertNotEquals(claudeWorkspace, whoami(other, null));
                } finally {
                    claude.closeGracefully();
                    chatGpt.closeGracefully();
                    other.closeGracefully();
                }

                java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
                String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"claude-ai\",\"version\":\"1\"}}}";
                java.util.List<String> rejected = new java.util.ArrayList<>(java.util.Arrays.asList(null,
                        "not-a-jwt", forger.token("angel", "argos"), issuer.token("angel", "other-scope")));
                for (String token : rejected) {
                    java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + running.port() + "/"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(initialize));
                    if (token != null) request.header("Authorization", "Bearer " + token);
                    assertEquals(401, http.send(request.build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode(), String.valueOf(token));
                }
                assertThrows(RuntimeException.class, () -> client(running.port(), "claude-ai", "not-a-jwt"));
            }
        }
    }
}
