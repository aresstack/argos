package com.aresstack.argos.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes results.jsonl (one line per run, appended as runs finish, so an aborted run keeps its data),
 * transcripts/, results.json (everything plus per-configuration totals) and summary.md (readable comparison).
 */
public final class EvalReport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path directory;
    private final List<RunRecord> records = new ArrayList<>();
    private final List<Warmup> warmups = new ArrayList<>();
    private final Map<String, List<String>> toolsByProfile = new LinkedHashMap<>();
    private final List<Skipped> skipped = new ArrayList<>();

    /** A model/profile pair that did not run at all. */
    public record Skipped(String configuration, String reason) {
    }

    /** The first request to a model: loads it into memory, measured apart from the tasks. */
    public record Warmup(String model, long loadMillis, long elapsedMillis, String error) {
    }

    /**
     * Totals of one model with one profile. The success rate counts only valid runs of tasks the profile can
     * solve; runs the profile cannot solve and invalid runs (model errors) are counted apart.
     */
    public record Configuration(String model, String profile, int runs, int solvableRuns, int notSolvableRuns,
                                int invalidRuns, int successes, double successRate,
                                int toolCalls, double toolCallsPerRun, int errors, int recoveryActions,
                                int recoveredRuns, int promptTokens, int completionTokens, int contextFullRuns,
                                long elapsedMillis, long modelMillis, long loadMillis, long toolMillis) {
    }

    public EvalReport(Path directory) throws IOException {
        this.directory = Files.createDirectories(directory);
        Files.createDirectories(directory.resolve("transcripts"));
    }

    public Path directory() {
        return directory;
    }

    public void addWarmup(Warmup warmup) {
        warmups.add(warmup);
    }

    public void addSkipped(String configuration, String reason) {
        skipped.add(new Skipped(configuration, reason));
    }

    /** False when a configuration did not run or a run hit a model error: the comparison is incomplete. */
    public boolean complete() {
        return skipped.isEmpty() && records.stream().allMatch(RunRecord::valid);
    }

    public String incompleteReason() {
        List<String> reasons = new ArrayList<>();
        skipped.forEach(skip -> reasons.add(skip.configuration() + " not run: " + skip.reason()));
        long invalid = records.stream().filter(record -> !record.valid()).count();
        if (invalid > 0) reasons.add(invalid + " run(s) ended with a model error");
        return String.join("; ", reasons);
    }

    public void addTools(String profile, List<String> names) {
        toolsByProfile.putIfAbsent(profile, List.copyOf(names));
    }

    public void add(RunRecord record, List<ChatMessage> transcript, List<String> fixtureRequests) throws IOException {
        records.add(record);
        try (Writer out = Files.newBufferedWriter(directory.resolve("results.jsonl"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            out.write(JSON.writeValueAsString(record));
            out.write('\n');
        }
        String name = (record.model() + "_" + record.profile() + "_" + record.task() + "_" + record.repetition())
                .replaceAll("[^A-Za-z0-9._-]+", "_") + ".json";
        PRETTY.writeValue(directory.resolve("transcripts").resolve(name).toFile(),
                Map.of("record", record, "messages", transcript, "fixtureRequests", fixtureRequests));
    }

    public List<RunRecord> records() {
        return List.copyOf(records);
    }

    /** Writes results.json and summary.md; returns the summary's path. */
    public Path finish(String settings) throws IOException {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("generatedAt", Instant.now().toString());
        all.put("settings", settings);
        all.put("complete", complete());
        if (!complete()) all.put("incomplete", incompleteReason());
        all.put("skipped", skipped);
        all.put("tools", toolsByProfile);
        all.put("warmups", warmups);
        all.put("configurations", configurations(records));
        all.put("records", records);
        PRETTY.writeValue(directory.resolve("results.json").toFile(), all);
        Path summary = directory.resolve("summary.md");
        Files.writeString(summary, summary(settings, toolsByProfile, warmups, skipped, records),
                StandardCharsets.UTF_8);
        return summary;
    }

    static List<Configuration> configurations(List<RunRecord> records) {
        Map<String, List<RunRecord>> groups = new LinkedHashMap<>();
        records.forEach(record -> groups.computeIfAbsent(record.model() + "@" + record.profile(),
                key -> new ArrayList<>()).add(record));
        List<Configuration> configurations = new ArrayList<>();
        for (List<RunRecord> group : groups.values()) {
            int runs = group.size();
            int invalid = (int) group.stream().filter(record -> !record.valid()).count();
            int notSolvable = (int) group.stream().filter(record -> record.valid() && !record.applicable()).count();
            int solvable = runs - invalid - notSolvable;
            int successes = (int) group.stream()
                    .filter(record -> record.valid() && record.applicable() && record.successful()).count();
            int calls = group.stream().mapToInt(RunRecord::toolCallCount).sum();
            configurations.add(new Configuration(group.get(0).model(), group.get(0).profile(), runs, solvable,
                    notSolvable, invalid, successes, solvable == 0 ? 0 : successes / (double) solvable,
                    calls, calls / (double) runs,
                    group.stream().mapToInt(RunRecord::errors).sum(),
                    group.stream().mapToInt(RunRecord::recoveryActions).sum(),
                    (int) group.stream().filter(RunRecord::recovered).count(),
                    group.stream().mapToInt(record -> Math.max(0, record.promptTokens())).sum(),
                    group.stream().mapToInt(record -> Math.max(0, record.completionTokens())).sum(),
                    (int) group.stream().filter(RunRecord::contextFull).count(),
                    group.stream().mapToLong(RunRecord::elapsedMillis).sum(),
                    group.stream().mapToLong(RunRecord::modelMillis).sum(),
                    group.stream().mapToLong(RunRecord::loadMillis).sum(),
                    group.stream().mapToLong(RunRecord::toolMillis).sum()));
        }
        return configurations;
    }

    static String summary(String settings, Map<String, List<String>> tools, List<Warmup> warmups,
                          List<Skipped> skipped, List<RunRecord> records) {
        StringBuilder out = new StringBuilder("# Argos model evaluation\n\n");
        long invalid = records.stream().filter(record -> !record.valid()).count();
        if (!skipped.isEmpty() || invalid > 0) {
            out.append("> **INCOMPLETE.** ");
            skipped.forEach(skip -> out.append('`').append(skip.configuration()).append("` did not run (")
                    .append(skip.reason()).append("). "));
            if (invalid > 0) out.append(invalid).append(" run(s) ended with a model error and are not counted. ");
            out.append("Do not compare these results as if they were complete.\n\n");
        }
        out.append("Settings: ").append(settings).append("\n\n");
        tools.forEach((profile, names) -> out.append("Tools of `").append(profile).append("` (").append(names.size())
                .append("): ").append(String.join(", ", names)).append("\n\n"));

        out.append("## Comparison\n\n| Model | Profile | Success (solvable tasks) | Not solvable | Invalid "
                + "| Tool calls (per task) | Errors | Recovery actions | Recovered runs | Tokens in/out | Context full "
                + "| Time s | Model s | Tools s | Load s |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Configuration c : configurations(records)) {
            out.append("| ").append(c.model()).append(" | ").append(c.profile()).append(" | **")
                    .append(c.successes()).append('/').append(c.solvableRuns()).append(String.format(Locale.ROOT,
                            "** (%.0f%%) | %d | %d | %d (%.1f) | %d | %d | %d | %d/%d | %d | %.1f | %.1f | %.1f | %.1f |%n",
                            c.successRate() * 100, c.notSolvableRuns(), c.invalidRuns(), c.toolCalls(),
                            c.toolCallsPerRun(), c.errors(),
                            c.recoveryActions(), c.recoveredRuns(), c.promptTokens(), c.completionTokens(),
                            c.contextFullRuns(), c.elapsedMillis() / 1000.0, c.modelMillis() / 1000.0,
                            c.toolMillis() / 1000.0, c.loadMillis() / 1000.0));
        }
        out.append("\nNot solvable: tasks the profile's tools cannot do (a form without form tools); they still run "
                + "and are listed, but do not count. Invalid: runs that ended with a model error. Context full: the "
                + "conversation reached 95 % of num_ctx, so the server probably dropped earlier messages. "
                + "Time = wall clock of the tasks; Model s excludes loading; Load s is loading during the tasks "
                + "(after the warm-up it should stay near 0).\n");

        if (!skipped.isEmpty()) {
            out.append("\n## Not run\n\n");
            skipped.forEach(skip -> out.append("- `").append(skip.configuration()).append("`: ").append(skip.reason())
                    .append('\n'));
        }

        if (!warmups.isEmpty()) {
            out.append("\n## Model warm-up (not part of the task times)\n\n| Model | Load s | First answer s | Error |\n"
                    + "|---|---|---|---|\n");
            for (Warmup warmup : warmups) {
                out.append(String.format(Locale.ROOT, "| %s | %.1f | %.1f | %s |%n", warmup.model(),
                        warmup.loadMillis() / 1000.0, warmup.elapsedMillis() / 1000.0,
                        warmup.error() == null ? "" : cell(warmup.error(), 200)));
            }
        }

        List<String> configurations = records.stream().map(r -> r.model() + "@" + r.profile()).distinct().toList();
        List<String> tasks = records.stream().map(RunRecord::task).distinct().toList();
        out.append("\n## Tasks\n\nEach cell: ✓/✗ per run with its tool calls; n/a = not solvable with the profile, "
                + "! = model error.\n\n| Task |");
        configurations.forEach(configuration -> out.append(' ').append(configuration).append(" |"));
        out.append("\n|---|").append("---|".repeat(configurations.size())).append('\n');
        for (String task : tasks) {
            out.append("| ").append(task).append(" |");
            for (String configuration : configurations) {
                List<String> cells = new ArrayList<>();
                for (RunRecord record : records) {
                    if (record.task().equals(task) && (record.model() + "@" + record.profile()).equals(configuration)) {
                        String mark = !record.valid() ? "! " : record.successful() ? "✓ " : "✗ ";
                        cells.add(mark + record.toolCallCount() + (record.applicable() ? "" : " n/a"));
                    }
                }
                out.append(' ').append(cells.isEmpty() ? "–" : String.join(", ", cells)).append(" |");
            }
            out.append('\n');
        }

        for (String configuration : configurations) {
            out.append("\n## ").append(configuration).append("\n\n| Task | Category | Run | OK | Tool calls | Errors "
                    + "| Recovery | Tokens in/out (max prompt, estimate) | Time s | Stop | Calls | Answer |\n"
                    + "|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (RunRecord record : records) {
                if (!(record.model() + "@" + record.profile()).equals(configuration)) continue;
                out.append("| ").append(record.task()).append(" | ").append(record.category()).append(" | ")
                        .append(record.repetition()).append(" | ").append(!record.valid() ? "invalid"
                                : record.successful() ? "yes" : record.applicable() ? "**no**" : "no (n/a)")
                        .append(" | ").append(record.toolCallCount())
                        .append(record.textualToolCalls() > 0 ? " (" + record.textualToolCalls() + " as text)" : "")
                        .append(" | ").append(record.errors()).append(" | ").append(record.recoveryActions())
                        .append(record.recovered() ? " (recovered)" : "").append(" | ")
                        .append(record.promptTokens() < 0 ? "n/a" : record.promptTokens() + "/"
                                + record.completionTokens() + " (" + record.maxPromptTokens() + ", est. "
                                + record.estimatedPromptTokens() + ")")
                        .append(record.contextFull() ? " **context full**" : "")
                        .append(" | ").append(String.format(Locale.ROOT, "%.1f", record.elapsedMillis() / 1000.0))
                        .append(" | ").append(record.stopReason()).append(" | ").append(callSequence(record))
                        .append(" | ").append(cell(record.finalAnswer(), 160)).append(" |\n");
            }
        }

        List<RunRecord> failed = records.stream().filter(record -> !record.successful()).toList();
        if (!failed.isEmpty()) {
            out.append("\n## Failures\n");
            for (RunRecord record : failed) {
                out.append("\n### ").append(record.model()).append('@').append(record.profile()).append(" · ")
                        .append(record.task()).append(" (run ").append(record.repetition()).append(")\n\n")
                        .append("Stop: ").append(record.stopReason()).append("\n\n");
                record.errorMessages().forEach(message -> out.append("- ").append(cell(message, 300)).append('\n'));
                for (RunRecord.Call call : record.toolCalls()) {
                    out.append("- turn ").append(call.turn()).append(": `").append(call.name()).append(' ')
                            .append(cell(call.arguments(), 160).replace("`", "'")).append('`')
                            .append(call.error() ? " ERROR" : call.setback() ? " SETBACK" : "")
                            .append(" → ").append(cell(call.resultPreview(), 160)).append('\n');
                }
                out.append("- answer: ").append(cell(record.finalAnswer(), 400)).append('\n');
            }
        }
        return out.toString();
    }

    private static String callSequence(RunRecord record) {
        List<String> names = new ArrayList<>();
        for (RunRecord.Call call : record.toolCalls()) {
            names.add(call.name().replaceFirst("^(web|browser)_", "") + (call.error() ? "✗" : call.setback() ? "!" : ""));
        }
        return names.isEmpty() ? "–" : String.join(" ", names);
    }

    private static String cell(String text, int limit) {
        String flat = (text == null ? "" : text).replaceAll("\\s+", " ").replace("|", "\\|").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
