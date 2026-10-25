package com.aresstack.argos.eval;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Evaluation settings. Each name is read as system property {@code argos.eval.<name>} (Gradle:
 * {@code -Peval.<name>=...}) or environment variable {@code ARGOS_EVAL_<NAME>} (dashes become underscores).
 *
 * <p>What runs: {@code runs} lists {@code model@profile} pairs. Without it, every {@code model} is combined
 * with every {@code profile} (both comma-separated, profile default compact). Without any model, the Phase 7
 * plan {@link #DEFAULT_RUNS} runs.
 */
public record EvalSettings(String api, String baseUrl, String apiKey, List<Target> targets, String tasks,
                           int repeat, int maxTurns, Duration taskTimeLimit, Duration requestTimeout,
                           GenerationSettings generation, boolean headless, Path outputDirectory) {

    /** Same tasks for both local models with the compact tools first, then the larger one with expert. */
    public static final String DEFAULT_RUNS = "gemma4:e2b@compact,gpt-oss:20b@compact,gpt-oss:20b@expert";

    /** One model with one tool profile. */
    public record Target(String model, EvalProfile profile) {
        public String id() {
            return model + "@" + profile.id();
        }
    }

    public EvalSettings {
        targets = List.copyOf(targets);
    }

    public static EvalSettings load() {
        return from(EvalSettings::lookup);
    }

    static EvalSettings from(Function<String, String> settings) {
        String api = value(settings, "api", "ollama").toLowerCase(Locale.ROOT);
        if (!api.equals("ollama") && !api.equals("openai")) {
            throw new IllegalArgumentException("eval.api must be 'ollama' or 'openai': " + api);
        }
        // 127.0.0.1, not localhost: on Windows localhost may resolve to ::1 first, where Ollama does not listen.
        String defaultUrl = api.equals("ollama") ? "http://127.0.0.1:11434" : "http://127.0.0.1:11434/v1";
        GenerationSettings defaults = GenerationSettings.defaults();
        GenerationSettings generation = new GenerationSettings(
                decimal(settings, "temperature", defaults.temperature()),
                integer(settings, "seed", defaults.seed(), Integer.MIN_VALUE, Integer.MAX_VALUE),
                integer(settings, "num-ctx", defaults.contextLength(), 2048, 262144),
                integer(settings, "max-output-tokens", defaults.maxOutputTokens(), 64, 131072),
                value(settings, "think", null), value(settings, "reasoning-effort", null));
        return new EvalSettings(api, value(settings, "base-url", defaultUrl), value(settings, "api-key", null),
                targets(settings), value(settings, "tasks", "fixtures"),
                integer(settings, "repeat", 1, 1, 50), integer(settings, "max-turns", 15, 1, 100),
                Duration.ofSeconds(integer(settings, "task-time-limit-s", 600, 10, 7200)),
                Duration.ofSeconds(integer(settings, "request-timeout-s", 300, 5, 3600)),
                generation, !"false".equalsIgnoreCase(value(settings, "headless", "true")),
                Path.of(value(settings, "out", "build/eval")));
    }

    private static List<Target> targets(Function<String, String> settings) {
        String runs = value(settings, "runs", null);
        String models = value(settings, "model", null);
        List<Target> targets = new ArrayList<>();
        if (runs == null && models != null) {
            for (String model : split(models)) {
                for (String profile : split(value(settings, "profile", "compact"))) {
                    targets.add(new Target(model, EvalProfile.parse(profile)));
                }
            }
            return targets;
        }
        for (String run : split(runs == null ? DEFAULT_RUNS : runs)) {
            int at = run.lastIndexOf('@');
            if (at <= 0 || at == run.length() - 1) {
                throw new IllegalArgumentException("eval.runs entries look like model@profile, e.g. gpt-oss:20b@compact: "
                        + run);
            }
            targets.add(new Target(run.substring(0, at).trim(), EvalProfile.parse(run.substring(at + 1))));
        }
        return targets;
    }

    public ChatModel chatModel(String model) {
        return api.equals("ollama")
                ? new OllamaChatModel(baseUrl, model, requestTimeout, generation)
                : new OpenAiChatModel(baseUrl, model, apiKey, requestTimeout, generation);
    }

    /** For the report: everything that shapes a run, never the API key. */
    public String describe() {
        return "api=" + api + ", baseUrl=" + baseUrl + ", runs=" + String.join(" ",
                targets.stream().map(Target::id).toList()) + ", tasks=" + tasks + ", repeat=" + repeat
                + ", maxTurns=" + maxTurns + ", " + generation.describe()
                + ", apiKey=" + (apiKey == null ? "none" : "set");
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) parts.add(part.trim());
        }
        return parts;
    }

    private static String lookup(String name) {
        String property = System.getProperty("argos.eval." + name);
        if (property != null && !property.isBlank()) return property.trim();
        String environment = System.getenv("ARGOS_EVAL_" + name.toUpperCase(Locale.ROOT).replace('-', '_'));
        return environment == null || environment.isBlank() ? null : environment.trim();
    }

    private static String value(Function<String, String> settings, String name, String fallback) {
        String value = settings.apply(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Integer integer(Function<String, String> settings, String name, Integer fallback, int min, int max) {
        String value = value(settings, name, null);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("eval." + name + " must be an integer from " + min + " to " + max
                    + ": " + value);
        }
    }

    private static Double decimal(Function<String, String> settings, String name, Double fallback) {
        String value = value(settings, name, null);
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("eval." + name + " must be a number: " + value);
        }
    }
}
