package com.aresstack.argos.eval;

/**
 * Generation parameters sent explicitly with every request, so a run never depends on the server's global
 * defaults (e.g. an Ollama context length of 256K).
 *
 * @param contextLength   Ollama num_ctx; ignored by OpenAI-compatible servers, which fix it at load time
 * @param maxOutputTokens Ollama num_predict / OpenAI max_tokens per answer
 * @param think           Ollama "think": true, false, or a level such as low/medium/high (gpt-oss); null = default
 * @param reasoningEffort OpenAI reasoning_effort; null = server default
 */
public record GenerationSettings(Double temperature, Integer seed, Integer contextLength, Integer maxOutputTokens,
                                 String think, String reasoningEffort) {
    public static GenerationSettings defaults() {
        return new GenerationSettings(0.0, 42, 8192, 4096, null, null);
    }

    public String describe() {
        return "temperature=" + temperature + ", seed=" + seed + ", num_ctx=" + contextLength
                + ", max_output_tokens=" + maxOutputTokens
                + (think == null ? "" : ", think=" + think)
                + (reasoningEffort == null ? "" : ", reasoning_effort=" + reasoningEffort);
    }
}
