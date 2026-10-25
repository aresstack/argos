package com.aresstack.argos.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalSettingsTest {
    private static List<String> ids(EvalSettings settings) {
        return settings.targets().stream().map(EvalSettings.Target::id).toList();
    }

    @Test
    void withoutModelsThePhaseSevenPlanRunsOnLocalOllamaWithExplicitGeneration() {
        EvalSettings settings = EvalSettings.from(Map.<String, String>of()::get);

        assertEquals(List.of("gemma4:e2b@compact", "gpt-oss:20b@compact", "gpt-oss:20b@expert"), ids(settings));
        assertEquals("http://127.0.0.1:11434", settings.baseUrl());
        assertEquals(new GenerationSettings(0.0, 42, 8192, 4096, null, null), settings.generation());
        assertEquals("fixtures", settings.tasks());
        assertInstanceOf(OllamaChatModel.class, settings.chatModel("gemma4:e2b"));
    }

    @Test
    void modelsAndProfilesAreCombinedOrGivenAsPairs() {
        assertEquals(List.of("a:1@compact", "a:1@expert", "b@compact", "b@expert"), ids(EvalSettings.from(
                Map.of("model", "a:1, b", "profile", "compact,expert")::get)));
        assertEquals(List.of("qwen2.5-coder:14b@compact-interact"), ids(EvalSettings.from(
                Map.of("runs", "qwen2.5-coder:14b@Compact-Interact")::get)));
        assertThrows(IllegalArgumentException.class, () -> EvalSettings.from(Map.of("runs", "gemma4:e2b")::get));
        assertThrows(IllegalArgumentException.class, () -> EvalSettings.from(Map.of("runs", "m@tiny")::get));
    }

    @Test
    void openAiEndpointKeepsTheKeyOutOfTheDescription() {
        EvalSettings settings = EvalSettings.from(Map.of("model", "openai/gpt-oss-120b", "api", "openai",
                "base-url", "https://openrouter.ai/api/v1", "api-key", "sk-123", "num-ctx", "16384")::get);

        assertInstanceOf(OpenAiChatModel.class, settings.chatModel("openai/gpt-oss-120b"));
        assertEquals(16384, settings.generation().contextLength());
        assertTrue(settings.describe().contains("apiKey=set"));
        assertFalse(settings.describe().contains("sk-123"));
    }
}
