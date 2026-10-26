package com.aresstack.argos.eval;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TextualToolCallsTest {
    private static final Set<String> TOOLS = Set.of("web_search", "web_open");

    @Test
    void readsHermesStyleTaggedCalls() {
        var calls = TextualToolCalls.parse("<think>search first</think>\n<tool_call>\n{\"name\": \"web_search\", "
                + "\"arguments\": {\"query\": \"kestrel\"}}\n</tool_call>", TOOLS);

        assertEquals(1, calls.size());
        assertEquals("web_search", calls.get(0).name());
        assertEquals("{\"query\":\"kestrel\"}", calls.get(0).arguments());
        assertTrue(calls.get(0).textual());
    }

    @Test
    void readsMistralFencedAndBareJson() {
        assertEquals("web_open", TextualToolCalls.parse("[TOOL_CALLS] [{\"name\": \"web_open\", \"arguments\": "
                + "{\"url\": \"http://a\"}}]", TOOLS).get(0).name());
        assertEquals("web_search", TextualToolCalls.parse("I will search.\n```json\n{\"name\": \"web_search\", "
                + "\"parameters\": {\"query\": \"x\"}}\n```", TOOLS).get(0).name());
        assertEquals("web_open", TextualToolCalls.parse("{\"name\": \"web_open\", \"arguments\": \"{\\\"url\\\": "
                + "\\\"http://a\\\"}\"}", TOOLS).get(0).name());
    }

    @Test
    void ignoresJsonThatNamesNoPublishedTool() {
        assertTrue(TextualToolCalls.parse("{\"name\": \"Margit Solberg\", \"year\": 1961}", TOOLS).isEmpty());
        assertTrue(TextualToolCalls.parse("The answer is 212 clear nights.", TOOLS).isEmpty());
    }

    @Test
    void removesInlineThinkingFromTheAnswer() {
        assertEquals("It is 212.", TextualToolCalls.withoutThinking("<think>maybe 150?</think>\nIt is 212."));
    }
}
