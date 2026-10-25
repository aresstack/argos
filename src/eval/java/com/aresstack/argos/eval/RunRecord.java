package com.aresstack.argos.eval;

import java.util.List;

/**
 * The measured result of one task run; one JSON line in results.jsonl.
 *
 * @param errors          tool calls that failed: tool errors, unknown tool names, arguments that are not a JSON object
 * @param recoveryActions tool calls made right after a setback (an error, "No results." or an HTTP error page)
 * @param recovered       the run hit at least one setback and still answered correctly
 * @param stopReason      answered, max_steps, empty_answer, model_error or time_limit
 * @param modelMillis     time spent waiting for the model, without model loading
 * @param loadMillis      time the model server spent loading the model (reported separately, Ollama only)
 * @param maxPromptTokens largest single prompt the server reported
 * @param estimatedPromptTokens largest conversation the harness sent, estimated from its characters (about 3.5
 *                        per token); the server may report less after cutting old messages to fit its context
 * @param contextFull     the reported or the estimated prompt reached 95% of the configured context length,
 *                        so the server probably dropped earlier messages (possibly the question itself)
 * @param applicable      the profile's tools can solve the task (false: e.g. a form task with compact tools)
 * @param valid           the model could be asked (false after a model or endpoint error): an invalid run
 *                        measures the server, not the model, and is reported apart from the success rate
 */
public record RunRecord(String task, String category, String model, String profile, int repetition,
                        boolean applicable, boolean valid,
                        boolean successful, String stopReason, String finalAnswer,
                        int toolCallCount, int errors, List<String> errorMessages, int recoveryActions,
                        boolean recovered, int textualToolCalls, int modelTurns,
                        int promptTokens, int completionTokens, int maxPromptTokens, int estimatedPromptTokens,
                        boolean contextFull,
                        long elapsedMillis, long modelMillis, long loadMillis, long toolMillis, List<Call> toolCalls) {

    /** One tool call as the model issued it and what came back (preview only; the transcript has everything). */
    public record Call(int turn, String name, String arguments, boolean error, boolean setback, boolean textual,
                       long elapsedMillis, int resultCharacters, String resultPreview) {
    }

    public int totalTokens() {
        return Math.max(0, promptTokens) + Math.max(0, completionTokens);
    }
}
