package com.aresstack.argos.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OpenAI-compatible {@code /chat/completions} with tools (vLLM, LM Studio, llama.cpp server, Ollama's /v1,
 * hosted GPT-OSS). The base URL includes the version path, e.g. {@code http://localhost:11434/v1}.
 */
public final class OpenAiChatModel implements ChatModel {
    private final String endpoint;
    private final String model;
    private final HttpJson http;
    private final GenerationSettings generation;

    public OpenAiChatModel(String baseUrl, String model, String apiKey, Duration timeout,
                           GenerationSettings generation) {
        this.endpoint = stripSlash(baseUrl) + "/chat/completions";
        this.model = model;
        this.http = new HttpJson(timeout, apiKey);
        this.generation = generation;
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public ModelTurn complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws ModelException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(OpenAiChatModel::wire).toList());
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(tool -> Map.of("type", "function", "function", Map.of(
                    "name", tool.name(), "description", tool.description(), "parameters", tool.inputSchema())))
                    .toList());
            body.put("tool_choice", "auto");
        }
        if (generation.temperature() != null) body.put("temperature", generation.temperature());
        if (generation.seed() != null) body.put("seed", generation.seed());
        if (generation.maxOutputTokens() != null) body.put("max_tokens", generation.maxOutputTokens());
        if (generation.reasoningEffort() != null) body.put("reasoning_effort", generation.reasoningEffort());
        body.put("stream", false);

        JsonNode answer = http.post(endpoint, body);
        JsonNode message = answer.path("choices").path(0).path("message");
        if (message.isMissingNode()) throw new ModelException("No choices[0].message in the answer of " + endpoint);
        String content = message.path("content").isNull() ? "" : message.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            JsonNode arguments = function.path("arguments");
            calls.add(new ToolCall(call.path("id").asText("call-" + (calls.size() + 1)),
                    function.path("name").asText(""),
                    arguments.isTextual() ? arguments.asText() : arguments.isMissingNode() ? "{}" : arguments.toString(),
                    false));
        }
        if (calls.isEmpty()) {
            Set<String> names = tools.stream().map(ToolDefinition::name).collect(Collectors.toSet());
            calls.addAll(TextualToolCalls.parse(content, names));
        }
        JsonNode usage = answer.path("usage");
        return new ModelTurn(content, calls, usage.path("prompt_tokens").asInt(-1),
                usage.path("completion_tokens").asInt(-1));
    }

    private static Map<String, Object> wire(ChatMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role());
        wire.put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(call -> Map.of("id", call.id(), "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.arguments()))).toList());
        }
        if (message.toolCallId() != null) wire.put("tool_call_id", message.toolCallId());
        if ("tool".equals(message.role()) && message.toolName() != null) wire.put("name", message.toolName());
        return wire;
    }

    static String stripSlash(String url) {
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }
}
