package com.aresstack.argos.eval;

import com.aresstack.argos.ArgosConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runs the task set for every configured model and tool profile against Argos and writes one report.
 * Exits with status 2 when the evaluation is incomplete (a model could not be loaded or failed mid-run), so a
 * partial result is never mistaken for a finished one. See docs/model-evaluation.md.
 */
public final class EvalMain {
    static final int INCOMPLETE = 2;

    private EvalMain() {
    }

    public static void main(String[] args) throws Exception {
        EvalSettings settings = EvalSettings.load();
        List<EvalTask> tasks = TaskCatalog.select(settings.tasks());
        System.out.println("Argos model evaluation: " + settings.describe());

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        EvalReport report = new EvalReport(settings.outputDirectory().resolve(stamp));
        Path browserProfile = Files.createTempDirectory("argos-eval-profile");
        Set<String> unavailable = new HashSet<>();

        try (FixtureWorld world = new FixtureWorld();
             ArgosUnderTest argos = new ArgosUnderTest(ArgosConfig.fromEnvironment(), browserProfile,
                     settings.headless(), world, settings.partCharacters())) {
            ChatModel loaded = null;
            for (EvalSettings.Target target : settings.targets()) {
                if (unavailable.contains(target.model())) {
                    report.addSkipped(target.id(), "model could not be loaded, see warm-up");
                    System.out.println("Skipping " + target.id() + ": model could not be loaded");
                    continue;
                }
                ChatModel model = settings.chatModel(target.model());
                if (loaded == null || !loaded.name().equals(target.model())) {
                    // Free the GPU for the next model; two models side by side would distort or prevent the run.
                    if (loaded != null) loaded.release();
                    EvalReport.Warmup warmup = warmUp(model);
                    report.addWarmup(warmup);
                    loaded = model;
                    if (warmup.error() != null) {
                        unavailable.add(target.model());
                        report.addSkipped(target.id(), "model could not be loaded, see warm-up");
                        System.out.println("Skipping " + target.id() + ": " + warmup.error());
                        continue;
                    }
                }
                for (int repetition = 1; repetition <= settings.repeat(); repetition++) {
                    for (EvalTask task : tasks) {
                        runTask(settings, report, world, argos, model, target, task, repetition);
                    }
                }
            }
            if (loaded != null) loaded.release();
        }
        Path summary = report.finish(settings.describe());
        for (EvalReport.Configuration c : EvalReport.configurations(report.records())) {
            System.out.printf(Locale.ROOT, "%s@%s: %d of %d solvable tasks, %.1f tool calls per task, %d errors%s%n",
                    c.model(), c.profile(), c.successes(), c.solvableRuns(), c.toolCallsPerRun(), c.errors(),
                    c.invalidRuns() > 0 ? ", " + c.invalidRuns() + " INVALID runs (model errors)" : "");
        }
        System.out.println("Report: " + summary.toAbsolutePath());
        if (!report.complete()) {
            System.err.println("INCOMPLETE evaluation: " + report.incompleteReason());
            System.exit(INCOMPLETE);
        }
    }

    private static void runTask(EvalSettings settings, EvalReport report, FixtureWorld world, ArgosUnderTest argos,
                                ChatModel model, EvalSettings.Target target, EvalTask task, int repetition)
            throws Exception {
        world.reset();
        if (task.setup() == EvalTask.Setup.FAIL_FIRST_SEARCH) world.failNextSearch();
        List<ChatMessage> transcript = new ArrayList<>();
        RunRecord record;
        try (ArgosUnderTest.Session session = argos.startSession(target.profile(), task.live());
             McpToolGateway gateway = new McpToolGateway(session.url(), Duration.ofSeconds(180), settings.hiddenTools())) {
            report.addTools(target.profile().id(), gateway.tools().stream().map(ToolDefinition::name).toList());
            record = new AgentLoop(model, gateway, settings.maxTurns(), settings.taskTimeLimit(),
                    "ollama".equals(settings.api()) ? settings.generation().contextLength() : null,
                    settings.systemPrompt())
                    .run(task, task.promptFor(world.siteBase()), target.profile().id(), repetition,
                            task.solvableWith(target.profile()), transcript);
        }
        report.add(record, transcript, task.live() ? List.of() : world.requests());
        System.out.printf(Locale.ROOT, "%-28s %-15s run %d: %-7s %2d calls, %d errors, %d recovery, %5.1f s, %s%n",
                target.id(), task.id(), repetition,
                !record.valid() ? "INVALID" : record.successful() ? "OK" : record.applicable() ? "FAIL" : "n/a",
                record.toolCallCount(), record.errors(), record.recoveryActions(), record.elapsedMillis() / 1000.0,
                record.stopReason());
    }

    /** Loads the model before the first task, so loading never counts as task time. */
    private static EvalReport.Warmup warmUp(ChatModel model) {
        long start = System.nanoTime();
        try {
            ModelTurn probe = model.complete(List.of(ChatMessage.user("Reply with the single word OK.")), List.of());
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.printf(Locale.ROOT, "%s ready: loaded in %.1f s, answered \"%s\"%n", model.name(),
                    probe.loadMillis() / 1000.0, TextualToolCalls.withoutThinking(probe.content()).lines().findFirst()
                            .orElse(""));
            return new EvalReport.Warmup(model.name(), probe.loadMillis(), elapsed, null);
        } catch (Exception failure) {
            return new EvalReport.Warmup(model.name(), 0, (System.nanoTime() - start) / 1_000_000,
                    failure.getMessage());
        }
    }
}
