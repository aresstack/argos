package com.aresstack.argos.eval;

import java.util.List;

/**
 * One conversation entry in a provider-neutral form; {@link ChatModel} implementations translate it into the
 * wire format of their API.
 *
 * @param toolCalls  calls the assistant requested (assistant messages only)
 * @param toolCallId the call this message answers (tool messages only; may be null for Ollama)
 * @param toolName   the tool that produced this message (tool messages only)
 * @param thinking   the assistant's separate reasoning; gpt-oss needs it back on its tool-calling turns
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName,
                          String thinking) {
    public ChatMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        thinking = thinking == null ? "" : thinking;
    }

    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
        this(role, content, toolCalls, toolCallId, toolName, "");
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

    public static ChatMessage assistant(ModelTurn turn) {
        return new ChatMessage("assistant", turn.content(), turn.toolCalls(), null, null, turn.thinking());
    }

    public static ChatMessage tool(ToolCall call, String content) {
        return new ChatMessage("tool", content, null, call.id(), call.name());
    }
}
