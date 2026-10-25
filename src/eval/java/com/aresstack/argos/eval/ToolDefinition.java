package com.aresstack.argos.eval;

import java.util.Map;

/** A tool as the model sees it: the MCP name, description and JSON input schema. */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
}
