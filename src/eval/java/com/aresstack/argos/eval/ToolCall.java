package com.aresstack.argos.eval;

/**
 * A tool call requested by the model.
 *
 * @param arguments the raw JSON object text exactly as the model produced it (may be invalid JSON)
 * @param textual   true when the call was recovered from the message text instead of the API's tool_calls
 */
public record ToolCall(String id, String name, String arguments, boolean textual) {
}
