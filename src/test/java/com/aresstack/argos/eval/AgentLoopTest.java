package com.aresstack.argos.eval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {
    private static final EvalTask TASK = new EvalTask("t", "test", "q", List.of("212"), List.of("150"),
            EvalTask.Setup.NONE);

    /** Plays back fixed model turns and records what the model was shown. */
    static final class ScriptedModel implements ChatModel {
        final Deque<ModelTurn> turns = new ArrayDeque<>();
        final List<Integer> messageCounts = new ArrayList<>();

        ScriptedModel(ModelTurn... turns) {
            this.turns.addAll(List.of(turns));
        }

        @Override
        public ModelTurn complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws ModelException {
            messageCounts.add(messages.size());
            if (turns.isEmpty()) throw new ModelException("script exhausted");
            return turns.poll();
        }

        @Override
        public String name() {
            return "scripted";
        }
    }

    static final class FakeTools implements ToolGateway {
        final List<String> calls = new ArrayList<>();

        @Override
        public List<ToolDefinition> tools() {
            return List.of(new ToolDefinition("web_search", "Search", Map.of("type", "object")),
                    new ToolDefinition("web_follow", "Follow", Map.of("type", "object")));
        }

        @Override
        public ToolOutcome call(String name, Map<String, Object> arguments) {
            calls.add(name + arguments);
            if (name.equals("web_search") && "first".equals(arguments.get("query"))) {
                return new ToolOutcome("web_search failed: no search engine returned a readable result page.", true);
            }
            if (name.equals("web_search")) return new ToolOutcome("result-1: Report — http://x/report", false);
            if (arguments.get("link_id").equals("result-9")) {
                return new ToolOutcome("URL: http://x/dead\nTITLE: 500 Internal Server Error\n\nInternal Server Error", false);
            }
            return new ToolOutcome("URL: http://x/report\n\n212 clear nights", false);
        }
    }

    private static ModelTurn call(String name, String arguments) {
        return new ModelTurn("", List.of(new ToolCall("c", name, arguments, false)), 100, 10);
    }

    @Test
    void countsErrorsSetbacksRecoveryAndTokens() {
        ScriptedModel model = new ScriptedModel(
                call("web_search", "{\"query\": \"first\"}"),
                call("web_search", "{\"query\": \"second\"}"),
                call("web_follow", "{\"link_id\": \"result-9\"}"),
                call("web_follow", "{\"link_id\": \"result-1\"}"),
                new ModelTurn("<think>done</think>There were 212 clear nights.", List.of(), 100, 10));
        FakeTools tools = new FakeTools();
        List<ChatMessage> transcript = new ArrayList<>();

        RunRecord record = new AgentLoop(model, tools, 10, Duration.ofMinutes(1), 8192)
                .run(TASK, "How many?", "compact", 1, transcript);

        assertTrue(record.successful());
        assertEquals("answered", record.stopReason());
        assertEquals("There were 212 clear nights.", record.finalAnswer());
        assertEquals(4, record.toolCallCount());
        assertEquals(1, record.errors());
        assertEquals(2, record.recoveryActions(), "the call after the failed search and after the 500 page");
        assertTrue(record.recovered());
        assertEquals(500, record.promptTokens());
        assertEquals(50, record.completionTokens());
        assertEquals(5, record.modelTurns());
        assertEquals(List.of(2, 4, 6, 8, 10), model.messageCounts, "every tool answer goes back to the model");
        assertEquals("tool", transcript.get(3).role());
    }

    @Test
    void unknownToolsAndInvalidArgumentsAreErrorsTheModelSees() {
        ScriptedModel model = new ScriptedModel(
                new ModelTurn("", List.of(new ToolCall("c", "browser_navigate", "{\"url\": \"http://x\"}", false)), -1, -1),
                new ModelTurn("", List.of(new ToolCall("c", "web_search", "query=kestrel", false)), -1, -1),
                new ModelTurn("I do not know.", List.of(), -1, -1));
        FakeTools tools = new FakeTools();
        List<ChatMessage> transcript = new ArrayList<>();

        RunRecord record = new AgentLoop(model, tools, 10, Duration.ofMinutes(1), 8192).run(TASK, "q", "compact", 1, transcript);

        assertFalse(record.successful());
        assertEquals(2, record.errors());
        assertTrue(tools.calls.isEmpty(), "neither call reaches Argos");
        assertTrue(transcript.get(3).content().startsWith("Unknown tool: browser_navigate. Available tools: web_follow"));
        assertTrue(transcript.get(5).content().contains("expected a JSON object"));
        assertEquals(-1, record.promptTokens(), "no usage reported is not zero tokens");
    }

    @Test
    void anAnswerWithoutAnyWorkingToolCallIsNotASuccessAndAFullContextIsFlagged() {
        ScriptedModel guessing = new ScriptedModel(new ModelTurn("212", List.of(), 8000, 3));
        RunRecord record = new AgentLoop(guessing, new FakeTools(), 5, Duration.ofMinutes(1), 8192)
                .run(TASK, "q", "compact", 1, new ArrayList<>());

        assertFalse(record.successful(), "the expected value alone is not enough without research");
        assertTrue(record.contextFull());
        assertEquals(8000, record.maxPromptTokens());
    }

    @Test
    void stopsAtTheTurnLimitAndOnModelErrors() {
        ScriptedModel looping = new ScriptedModel(call("web_search", "{\"query\": \"a\"}"),
                call("web_search", "{\"query\": \"b\"}"), call("web_search", "{\"query\": \"c\"}"));
        RunRecord limited = new AgentLoop(looping, new FakeTools(), 2, Duration.ofMinutes(1), 8192)
                .run(TASK, "q", "compact", 1, new ArrayList<>());
        assertEquals("max_steps", limited.stopReason());
        assertEquals(2, limited.toolCallCount());

        RunRecord failed = new AgentLoop(new ScriptedModel(), new FakeTools(), 5, Duration.ofMinutes(1), 8192)
                .run(TASK, "q", "compact", 1, new ArrayList<>());
        assertEquals("model_error", failed.stopReason());
        assertEquals(List.of("model: script exhausted"), failed.errorMessages());
    }

    @Test
    void summaryListsEveryRunAndExplainsFailures() {
        ScriptedModel model = new ScriptedModel(call("web_search", "{\"query\": \"first\"}"),
                new ModelTurn("About 150.", List.of(), 10, 1));
        RunRecord failed = new AgentLoop(model, new FakeTools(), 5, Duration.ofMinutes(1), 8192)
                .run(TASK, "q", "compact", 1, new ArrayList<>());

        String summary = EvalReport.summary("model=x", Map.of("compact", List.of("web_search")), List.of(), List.of(failed));

        assertTrue(summary.contains("| scripted | compact | **0/1** (0%) | 1 (1.0) | 1 | 0 | 0 | 110/11 |"), summary);
        assertTrue(summary.contains("| t | ✗ 1 |"));
        assertTrue(summary.contains("| t | test | 1 | **no** | 1 | 1 | 0 | 110/11 (100) |"));
        assertTrue(summary.contains("## Failures"));
        assertTrue(summary.contains("`web_search {\"query\": \"first\"}` ERROR"));
    }
}
