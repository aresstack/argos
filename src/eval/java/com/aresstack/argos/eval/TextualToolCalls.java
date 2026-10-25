package com.aresstack.argos.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers tool calls that a small model wrote into its message text instead of the API's tool_calls field,
 * as chat clients for local models do: Hermes/Qwen {@code <tool_call>{...}</tool_call>}, Mistral
 * {@code [TOOL_CALLS] [...]}, a fenced JSON block, or a message that is only a JSON object. Only names of
 * published tools count, so ordinary JSON in an answer is never mistaken for a call.
 */
public final class TextualToolCalls {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TAGGED = Pattern.compile("<tool_call>\\s*(.*?)\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern FENCED = Pattern.compile("```(?:json)?\\s*(\\{.*?}|\\[.*?])\\s*```", Pattern.DOTALL);
    private static final Pattern THINKING = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    private TextualToolCalls() {
    }

    /** The visible answer: reasoning blocks some local models emit inline are removed. */
    public static String withoutThinking(String content) {
        return THINKING.matcher(content == null ? "" : content).replaceAll("").strip();
    }

    public static List<ToolCall> parse(String content, Set<String> toolNames) {
        String text = withoutThinking(content);
        List<ToolCall> calls = new ArrayList<>();
        Matcher tagged = TAGGED.matcher(text);
        while (tagged.find()) addCalls(tagged.group(1), toolNames, calls);
        if (!calls.isEmpty()) return calls;
        int mistral = text.indexOf("[TOOL_CALLS]");
        if (mistral >= 0) {
            addCalls(text.substring(mistral + "[TOOL_CALLS]".length()).strip(), toolNames, calls);
            if (!calls.isEmpty()) return calls;
        }
        Matcher fenced = FENCED.matcher(text);
        while (fenced.find()) addCalls(fenced.group(1), toolNames, calls);
        if (!calls.isEmpty()) return calls;
        if (text.startsWith("{") || text.startsWith("[")) addCalls(text, toolNames, calls);
        return calls;
    }

    private static void addCalls(String json, Set<String> toolNames, List<ToolCall> calls) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (Exception notJson) {
            return;
        }
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(element -> addCall(element, toolNames, calls));
        } else {
            addCall(node, toolNames, calls);
        }
    }

    private static void addCall(JsonNode node, Set<String> toolNames, List<ToolCall> calls) {
        JsonNode function = node.has("function") ? node.get("function") : node;
        String name = function.path("name").asText("");
        if (!toolNames.contains(name)) return;
        JsonNode arguments = function.has("arguments") ? function.get("arguments") : function.get("parameters");
        String argumentText = arguments == null || arguments.isNull() ? "{}"
                : arguments.isTextual() ? arguments.asText() : arguments.toString();
        calls.add(new ToolCall("text-" + (calls.size() + 1), name, argumentText, true));
    }
}
