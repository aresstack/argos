package com.aresstack.argos.eval;

import java.util.List;

/**
 * One conversation entry in a provider-neutral form; {@link ChatModel} implementations translate it into the
 * wire format of their API.
 *
 * @param toolCalls  calls the assistant requested (assistant messages only)
 * @param toolCallId the call this message answers (tool messages only; may be null for Ollama)
 * @param toolName   the tool that produced this message (tool messages only)
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
    public ChatMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> calls) {
        return new ChatMessage("assistant", content, calls, null, null);
    }

    public static ChatMessage tool(ToolCall call, String content) {
        return new ChatMessage("tool", content, null, call.id(), call.name());
    }
}
