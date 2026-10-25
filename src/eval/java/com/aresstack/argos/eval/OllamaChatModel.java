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
 * Ollama's native {@code /api/chat}. Unlike Ollama's OpenAI endpoint it accepts {@code num_ctx}: Ollama's
 * default context is small and silently cuts long tool answers, which would measure the server, not Argos.
 */
public final class OllamaChatModel implements ChatModel {
    private final String endpoint;
    private final String model;
    private final HttpJson http;
    private final GenerationSettings generation;

    public OllamaChatModel(String baseUrl, String model, Duration timeout, GenerationSettings generation) {
        this.endpoint = OpenAiChatModel.stripSlash(baseUrl) + "/api/chat";
        this.model = model;
        this.http = new HttpJson(timeout, null);
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
        body.put("messages", messages.stream().map(OllamaChatModel::wire).toList());
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(tool -> Map.of("type", "function", "function", Map.of(
                    "name", tool.name(), "description", tool.description(), "parameters", tool.inputSchema())))
                    .toList());
        }
        body.put("stream", false);
        // Keep the model loaded between the turns and tasks of a run; loading is measured separately.
        body.put("keep_alive", "15m");
        if (generation.think() != null) body.put("think", thinkValue(generation.think()));
        Map<String, Object> options = new LinkedHashMap<>();
        if (generation.temperature() != null) options.put("temperature", generation.temperature());
        if (generation.seed() != null) options.put("seed", generation.seed());
        if (generation.contextLength() != null) options.put("num_ctx", generation.contextLength());
        if (generation.maxOutputTokens() != null) options.put("num_predict", generation.maxOutputTokens());
        if (!options.isEmpty()) body.put("options", options);

        JsonNode answer = http.post(endpoint, body);
        JsonNode message = answer.path("message");
        if (message.isMissingNode()) throw new ModelException("No message in the answer of " + endpoint);
        String content = message.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            JsonNode arguments = function.path("arguments");
            calls.add(new ToolCall("call-" + (calls.size() + 1), function.path("name").asText(""),
                    arguments.isTextual() ? arguments.asText() : arguments.isMissingNode() ? "{}" : arguments.toString(),
                    false));
        }
        if (calls.isEmpty()) {
            Set<String> names = tools.stream().map(ToolDefinition::name).collect(Collectors.toSet());
            calls.addAll(TextualToolCalls.parse(content, names));
        }
        return new ModelTurn(content, calls, answer.path("prompt_eval_count").asInt(-1),
                answer.path("eval_count").asInt(-1), answer.path("load_duration").asLong(0) / 1_000_000);
    }

    /** "true"/"false" as booleans; a level such as "low" (gpt-oss) as text. */
    private static Object thinkValue(String think) {
        return think.equalsIgnoreCase("true") || think.equalsIgnoreCase("false") ? Boolean.valueOf(think) : think;
    }

    private static Map<String, Object> wire(ChatMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role());
        wire.put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(call -> Map.of("function",
                    Map.of("name", call.name(), "arguments", argumentsObject(call.arguments())))).toList());
        }
        if ("tool".equals(message.role()) && message.toolName() != null) wire.put("tool_name", message.toolName());
        return wire;
    }

    /** Ollama expects arguments as an object; invalid JSON from the model is passed on as an empty object. */
    private static Object argumentsObject(String arguments) {
        try {
            JsonNode node = HttpJson.JSON.readTree(arguments);
            return node != null && node.isObject() ? node : Map.of();
        } catch (Exception invalid) {
            return Map.of();
        }
    }
}
