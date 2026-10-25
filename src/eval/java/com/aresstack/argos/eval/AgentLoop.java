package com.aresstack.argos.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The chat client under test, kept as plain as a typical local chat client: system prompt, user question,
 * then tool calls until the model answers without one. No hidden hints, retries or nudges, so what is measured
 * is the model with Argos' tools, not the harness.
 */
public final class AgentLoop {
    public static final String SYSTEM_PROMPT = """
            You are a research assistant with web browsing tools. Answer the user's question with facts you read \
            through the tools; do not guess and do not answer from memory. If a tool fails or a page is not \
            helpful, try another way. When you know the answer, reply with a short final answer (one or two \
            sentences) without calling a tool.""";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** HTTP error pages as Argos shows them: the page text, near the top. */
    private static final Pattern ERROR_PAGE = Pattern.compile(
            "(?i)\\b(500 internal server error|503 service unavailable|404 not found|internal server error|"
                    + "service unavailable)\\b");

    private final ChatModel model;
    private final ToolGateway tools;
    private final int maxTurns;
    private final Duration timeLimit;
    private final Integer contextLength;

    public AgentLoop(ChatModel model, ToolGateway tools, int maxTurns, Duration timeLimit, Integer contextLength) {
        this.model = model;
        this.tools = tools;
        this.maxTurns = maxTurns;
        this.timeLimit = timeLimit;
        this.contextLength = contextLength;
    }

    /** Runs one task; the transcript receives every message for later inspection. */
    public RunRecord run(EvalTask task, String prompt, String profile, int repetition, List<ChatMessage> transcript) {
        List<ToolDefinition> definitions = tools.tools();
        Set<String> names = definitions.stream().map(ToolDefinition::name).collect(Collectors.toSet());
        transcript.add(ChatMessage.system(SYSTEM_PROMPT));
        transcript.add(ChatMessage.user(prompt));

        long started = System.nanoTime();
        long modelNanos = 0;
        long loadMillis = 0;
        int maxPrompt = -1;
        long toolNanos = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        boolean tokensReported = false;
        List<RunRecord.Call> calls = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();
        int errors = 0;
        int recoveryActions = 0;
        int setbacks = 0;
        int textual = 0;
        boolean lastWasSetback = false;
        String answer = "";
        String stop = "max_steps";
        int turn = 0;

        while (turn < maxTurns) {
            if (System.nanoTime() - started > timeLimit.toNanos()) {
                stop = "time_limit";
                break;
            }
            turn++;
            ModelTurn reply;
            long modelStart = System.nanoTime();
            try {
                reply = model.complete(transcript, definitions);
            } catch (Exception failure) {
                modelNanos += System.nanoTime() - modelStart;
                errorMessages.add("model: " + failure.getMessage());
                stop = "model_error";
                break;
            }
            modelNanos += System.nanoTime() - modelStart;
            loadMillis += reply.loadMillis();
            maxPrompt = Math.max(maxPrompt, reply.promptTokens());
            if (reply.promptTokens() >= 0 || reply.completionTokens() >= 0) tokensReported = true;
            promptTokens += Math.max(0, reply.promptTokens());
            completionTokens += Math.max(0, reply.completionTokens());

            if (reply.toolCalls().isEmpty()) {
                answer = TextualToolCalls.withoutThinking(reply.content());
                transcript.add(ChatMessage.assistant(reply.content(), List.of()));
                stop = answer.isBlank() ? "empty_answer" : "answered";
                break;
            }
            transcript.add(ChatMessage.assistant(reply.content(), reply.toolCalls()));
            for (ToolCall call : reply.toolCalls()) {
                if (call.textual()) textual++;
                if (lastWasSetback) recoveryActions++;
                long toolStart = System.nanoTime();
                ToolGateway.ToolOutcome outcome = execute(call, names);
                long toolElapsed = System.nanoTime() - toolStart;
                toolNanos += toolElapsed;
                boolean setback = outcome.error() || isSetback(outcome.text());
                if (outcome.error()) {
                    errors++;
                    errorMessages.add(call.name() + ": " + firstLine(outcome.text()));
                }
                if (setback) setbacks++;
                lastWasSetback = setback;
                calls.add(new RunRecord.Call(turn, call.name(), call.arguments(), outcome.error(), setback,
                        call.textual(), toolElapsed / 1_000_000, outcome.text().length(), preview(outcome.text())));
                transcript.add(ChatMessage.tool(call, outcome.text()));
            }
        }
        // An answer counts only if it was researched: at least one tool call must have worked.
        boolean researched = calls.stream().anyMatch(call -> !call.error());
        boolean successful = "answered".equals(stop) && researched && task.accepts(answer);
        boolean contextFull = contextLength != null && maxPrompt >= contextLength * 0.95;
        return new RunRecord(task.id(), task.category(), model.name(), profile, repetition, successful, stop, answer,
                calls.size(), errors, List.copyOf(errorMessages), recoveryActions, successful && setbacks > 0, textual,
                turn, tokensReported ? promptTokens : -1, tokensReported ? completionTokens : -1, maxPrompt,
                contextFull, (System.nanoTime() - started) / 1_000_000,
                Math.max(0, modelNanos / 1_000_000 - loadMillis), loadMillis, toolNanos / 1_000_000,
                List.copyOf(calls));
    }

    private ToolGateway.ToolOutcome execute(ToolCall call, Set<String> names) {
        if (!names.contains(call.name())) {
            return new ToolGateway.ToolOutcome("Unknown tool: " + call.name() + ". Available tools: "
                    + String.join(", ", names.stream().sorted().toList()), true);
        }
        Map<String, Object> arguments;
        try {
            JsonNode node = call.arguments() == null || call.arguments().isBlank()
                    ? JSON.createObjectNode() : JSON.readTree(call.arguments());
            if (node == null || !node.isObject()) throw new IllegalArgumentException("not an object");
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.convertValue(node, Map.class);
            arguments = parsed;
        } catch (Exception invalid) {
            return new ToolGateway.ToolOutcome("Invalid arguments for " + call.name()
                    + ": expected a JSON object such as {\"url\": \"https://example.org\"}", true);
        }
        return tools.call(call.name(), arguments);
    }

    /** Not an error flag, but a dead end the model has to work around. */
    static boolean isSetback(String text) {
        String head = text.length() > 600 ? text.substring(0, 600) : text;
        return head.startsWith("No results") || head.contains("\nNo results.") || ERROR_PAGE.matcher(head).find();
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() <= 200 ? line : line.substring(0, 200) + "...";
    }

    private static String preview(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 240 ? flat : flat.substring(0, 240) + "...";
    }
}
