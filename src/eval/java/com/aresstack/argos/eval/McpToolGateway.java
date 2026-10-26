package com.aresstack.argos.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calls Argos as a real MCP client over Streamable HTTP, exactly as a chat client would. */
public final class McpToolGateway implements ToolGateway, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final McpSyncClient client;
    private final List<ToolDefinition> tools;

    public McpToolGateway(String baseUrl, Duration requestTimeout) {
        this(baseUrl, requestTimeout, Set.of());
    }

    /** {@code hidden}: published tools the model is not offered, for A/B runs against an older tool set. */
    public McpToolGateway(String baseUrl, Duration requestTimeout, Set<String> hidden) {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl).endpoint("/").build())
                .requestTimeout(requestTimeout)
                .clientInfo(new McpSchema.Implementation("argos-eval", "1.0")).build();
        client.initialize();
        List<ToolDefinition> listed = new ArrayList<>();
        for (McpSchema.Tool tool : client.listTools().tools()) {
            if (hidden.contains(tool.name())) continue;
            listed.add(new ToolDefinition(tool.name(), tool.description() == null ? "" : tool.description(),
                    schemaOf(tool.inputSchema())));
        }
        tools = List.copyOf(listed);
    }

    @Override
    public List<ToolDefinition> tools() {
        return tools;
    }

    @Override
    public ToolOutcome call(String name, Map<String, Object> arguments) {
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(name, arguments));
            StringBuilder text = new StringBuilder();
            for (McpSchema.Content content : result.content()) {
                if (!text.isEmpty()) text.append('\n');
                if (content instanceof McpSchema.TextContent textContent) {
                    text.append(textContent.text());
                } else if (content instanceof McpSchema.ImageContent image) {
                    // The text-only chat APIs used here cannot pass images back to the model.
                    text.append("[image ").append(image.mimeType()).append(" not shown to the model]");
                } else {
                    text.append('[').append(content.type()).append(" content]");
                }
            }
            return new ToolOutcome(text.toString(), Boolean.TRUE.equals(result.isError()));
        } catch (Exception failure) {
            return new ToolOutcome("MCP call failed: " + failure.getMessage(), true);
        }
    }

    /** The JSON schema the model sees: only the parts that carry meaning, no null or empty members. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> schemaOf(McpSchema.JsonSchema schema) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", schema == null || schema.type() == null ? "object" : schema.type());
        map.put("properties", schema == null || schema.properties() == null ? Map.of() : schema.properties());
        if (schema != null && schema.required() != null && !schema.required().isEmpty()) {
            map.put("required", schema.required());
        }
        return JSON.convertValue(map, Map.class);
    }

    @Override
    public void close() {
        client.closeGracefully();
    }
}
