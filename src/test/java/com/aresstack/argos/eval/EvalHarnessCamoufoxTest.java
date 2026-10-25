package com.aresstack.argos.eval;

import com.aresstack.argos.ArgosConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aresstack.argos.eval.ReferenceAgent.find;
import static com.aresstack.argos.eval.ReferenceAgent.idOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in (-PtestCamoufox=true): the whole evaluation path (fixture world, real browser, MCP over HTTP, agent loop,
 * report) with a scripted reference agent instead of a language model. Every fixture task must be solvable.
 */
class EvalHarnessCamoufoxTest {
    @TempDir Path temporary;
    /** Base URL of the running fixture world, read by the scripts. */
    private static volatile String site;

    private static final Map<String, ReferenceAgent.Script> SOLUTIONS = Map.of(
            "find-site", steps -> {
                String results = steps.tool("web_search", "{\"query\": \"Kestrel Valley Observatory official website\"}");
                return "The official website is " + find(results, "Official Website — (\\S+)");
            },
            "search-pick", steps -> {
                String results = steps.tool("web_search", "{\"query\": \"Kestrel Valley Observatory clear nights 2025\"}");
                String report = steps.tool("web_follow", "{\"link_id\": \"" + idOf(results, "Annual Report") + "\"}");
                return find(report, "(\\d+) clear nights") + " clear nights according to the annual report.";
            },
            "follow-link", steps -> {
                steps.tool("web_open", "{\"url\": \"" + site + "/kvo/\"}");
                String links = steps.tool("web_links", "{}");
                String table = steps.tool("web_follow", "{\"link_id\": \"" + idOf(links, "Instruments") + "\"}");
                assertTrue(table.contains("1.8 m"), table);
                return "The Halvard Reflector (1.8 m).";
            },
            "dynamic-page", steps -> {
                String page = steps.tool("web_open", "{\"url\": \"" + site + "/kvo/events\"}");
                for (int attempt = 0; attempt < 5 && !page.contains("Next public lecture"); attempt++) {
                    Thread.sleep(1000); // a language model needs at least this long for its next turn
                    page = steps.tool("web_read", "{}");
                }
                return "The next public lecture is given by " + find(page, "with (Dr\\. \\w+ \\w+)");
            },
            "search-retry", steps -> {
                String results = steps.tool("web_search", "{\"query\": \"Kestrel Valley Observatory opening hours Saturday\"}");
                if (!results.contains("result-1")) {
                    results = steps.tool("web_search", "{\"query\": \"Kestrel Valley Observatory opening hours Saturday\"}");
                }
                String home = steps.tool("web_follow", "{\"link_id\": \"" + idOf(results, "Official Website") + "\"}");
                return "Saturday " + find(home, "Saturday (\\d\\d:\\d\\d–\\d\\d:\\d\\d)");
            },
            "broken-link", steps -> {
                String results = steps.tool("web_search", "{\"query\": \"Kestrel Valley Observatory dark-sky zone\"}");
                String official = steps.tool("web_follow", "{\"link_id\": \"" + idOf(results, "protection zone map") + "\"}");
                assertTrue(official.contains("Internal Server Error"), official);
                String mirror = steps.tool("web_follow", "{\"link_id\": \"" + idOf(results, "(mirror)") + "\"}");
                return "It is the " + find(mirror, "named the (\\w+ Dark-Sky Zone)");
            },
            "simple-form", steps -> {
                steps.tool("web_open", "{\"url\": \"" + site + "/kvo/tickets\"}");
                String controls = steps.tool("web_controls", "{}");
                assertTrue(controls.contains("Ticket number"), controls);
                steps.tool("web_fill", "{\"target\": \"Ticket number\", \"text\": \"KV-4471\"}");
                steps.tool("web_click", "{\"target\": \"Check status\"}");
                String status = steps.tool("web_read", "{}");
                return find(status, "(Ticket KV-4471: CONFIRMED\\. Seat F12)");
            },
            "long-page", steps -> {
                String part = steps.tool("web_open", "{\"url\": \"" + site + "/kvo/archive\"}");
                for (int next = 2; !part.contains("founding director") && part.contains("web_read part="); next++) {
                    part = steps.tool("web_read", "{\"part\": " + next + "}");
                }
                return find(part, "founding director was (\\w+ \\w+)");
            });

    @Test @Timeout(value = 400, unit = TimeUnit.SECONDS)
    void everyFixtureTaskIsSolvableThroughArgosOverMcp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        EvalReport report = new EvalReport(temporary.resolve("report"));
        try (FixtureWorld world = new FixtureWorld();
             ArgosUnderTest argos = new ArgosUnderTest(ArgosConfig.fromEnvironment(), temporary.resolve("profile"),
                     true, world)) {
            for (EvalTask task : TaskCatalog.fixtures()) {
                world.reset();
                if (task.setup() == EvalTask.Setup.FAIL_FIRST_SEARCH) world.failNextSearch();
                site = world.siteBase();
                List<ChatMessage> transcript = new ArrayList<>();
                try (ArgosUnderTest.Session session = argos.startSession(EvalProfile.COMPACT_INTERACT, false);
                     McpToolGateway gateway = new McpToolGateway(session.url(), Duration.ofSeconds(120))) {
                    report.addTools("compact-interact", gateway.tools().stream().map(ToolDefinition::name).toList());
                    RunRecord record = new AgentLoop(new ReferenceAgent(SOLUTIONS.get(task.id())), gateway, 15,
                            Duration.ofMinutes(3), null).run(task, task.promptFor(world.siteBase()),
                            "compact-interact", 1, transcript);
                    report.add(record, transcript, world.requests());
                }
            }
        }
        report.finish("reference agent");
        String summary = Files.readString(report.directory().resolve("summary.md"));
        System.out.println(summary);
        for (RunRecord record : report.records()) {
            assertTrue(record.successful(), record.task() + ": " + record.finalAnswer() + " " + record.toolCalls());
        }
        RunRecord retry = report.records().stream().filter(r -> r.task().equals("search-retry")).findFirst().orElseThrow();
        assertTrue(retry.recovered(), "the failed first search must be visible as a setback");
        RunRecord broken = report.records().stream().filter(r -> r.task().equals("broken-link")).findFirst().orElseThrow();
        assertTrue(broken.recovered(), "the HTTP 500 page must be visible as a setback");
        assertTrue(Files.exists(report.directory().resolve("results.json")));
    }
}
