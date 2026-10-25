package com.aresstack.argos.eval;

import java.util.List;

/**
 * One model answer.
 *
 * @param promptTokens     tokens the provider counted for the input, or -1 if it reported none
 * @param completionTokens tokens the provider counted for the output, or -1 if it reported none
 * @param loadMillis       time the server spent loading the model for this request (Ollama only, else 0)
 */
public record ModelTurn(String content, List<ToolCall> toolCalls, int promptTokens, int completionTokens,
                        long loadMillis) {
    public ModelTurn {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ModelTurn(String content, List<ToolCall> toolCalls, int promptTokens, int completionTokens) {
        this(content, toolCalls, promptTokens, completionTokens, 0);
    }
}
