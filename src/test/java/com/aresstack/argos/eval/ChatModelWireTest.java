package com.aresstack.argos.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The request bodies both providers receive and how their answers are read. */
class ChatModelWireTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ToolDefinition> TOOLS = List.of(new ToolDefinition("web_open", "Open a page",
            Map.of("type", "object", "properties", Map.of("url", Map.of("type", "string")))));
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;

    private String serve(String path, int status, String answer) throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            received.set(JSON.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static List<ChatMessage> conversation() {
        ToolCall call = new ToolCall("call-1", "web_open", "{\"url\":\"http://a\"}", false);
        return List.of(ChatMessage.system("s"), ChatMessage.user("u"), ChatMessage.assistant("", List.of(call)),
                ChatMessage.tool(call, "page text"));
    }

    @Test
    void openAiCompatibleRequestAndToolCallAnswer() throws Exception {
        String base = serve("/v1/chat/completions", 200, """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"x1","type":"function",
                "function":{"name":"web_open","arguments":"{\\"url\\": \\"http://b\\"}"}}]}}],
                "usage":{"prompt_tokens":321,"completion_tokens":12}}""");
        OpenAiChatModel model = new OpenAiChatModel(base + "/v1/", "gpt-oss:120b", "secret-key", Duration.ofSeconds(5),
                new GenerationSettings(0.0, 42, 8192, 4096, null, "low"));

        ModelTurn turn = model.complete(conversation(), TOOLS);

        assertEquals("Bearer secret-key", authorization.get());
        JsonNode body = received.get();
        assertEquals("gpt-oss:120b", body.path("model").asText());
        assertEquals("low", body.path("reasoning_effort").asText());
        assertEquals(42, body.path("seed").asInt());
        assertEquals(4096, body.path("max_tokens").asInt());
        assertEquals("web_open", body.path("tools").path(0).path("function").path("name").asText());
        assertEquals("{\"url\":\"http://a\"}", body.path("messages").path(2).path("tool_calls").path(0)
                .path("function").path("arguments").asText(), "OpenAI expects arguments as a JSON string");
        assertEquals("call-1", body.path("messages").path(3).path("tool_call_id").asText());
        assertEquals("x1", turn.toolCalls().get(0).id());
        assertEquals("{\"url\": \"http://b\"}", turn.toolCalls().get(0).arguments());
        assertEquals(321, turn.promptTokens());
        assertEquals(12, turn.completionTokens());
    }

    @Test
    void ollamaNativeRequestSetsContextAndReadsObjectArguments() throws Exception {
        String base = serve("/api/chat", 200, """
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"web_open",
                "arguments":{"url":"http://b"}}}]},"prompt_eval_count":900,"eval_count":30,"load_duration":2500000000}""");
        OllamaChatModel model = new OllamaChatModel(base, "gpt-oss:20b", Duration.ofSeconds(5),
                new GenerationSettings(0.0, 42, 8192, 4096, "low", null));

        ModelTurn turn = model.complete(conversation(), TOOLS);

        JsonNode body = received.get();
        assertEquals(8192, body.path("options").path("num_ctx").asInt());
        assertEquals(0.0, body.path("options").path("temperature").asDouble(), 0.0);
        assertEquals(42, body.path("options").path("seed").asInt());
        assertEquals(4096, body.path("options").path("num_predict").asInt());
        assertEquals("low", body.path("think").asText());
        assertEquals("15m", body.path("keep_alive").asText());
        assertEquals("http://a", body.path("messages").path(2).path("tool_calls").path(0).path("function")
                .path("arguments").path("url").asText(), "Ollama expects arguments as an object");
        assertEquals("web_open", body.path("messages").path(3).path("tool_name").asText());
        assertEquals("{\"url\":\"http://b\"}", turn.toolCalls().get(0).arguments());
        assertEquals(900, turn.promptTokens());
        assertEquals(2500, turn.loadMillis());
    }

    @Test
    void textualToolCallsAreRecoveredWhenTheServerDoesNotParseThem() throws Exception {
        String base = serve("/api/chat", 200, """
                {"message":{"role":"assistant","content":"<tool_call>{\\"name\\": \\"web_open\\", \\"arguments\\": {\\"url\\": \\"http://c\\"}}</tool_call>"}}""");
        ModelTurn turn = new OllamaChatModel(base, "gemma4:e2b", Duration.ofSeconds(5),
                new GenerationSettings(null, null, null, null, "false", null))
                .complete(conversation(), TOOLS);

        assertTrue(turn.toolCalls().get(0).textual());
        assertFalse(received.get().path("think").asBoolean(true), "think=false is sent as a boolean");
        assertFalse(received.get().has("options"));
        assertEquals(-1, turn.promptTokens());
    }

    @Test
    void httpErrorsNameTheEndpointButNeverTheKey() throws Exception {
        String base = serve("/v1/chat/completions", 401, "{\"error\":\"invalid key secret-key\"}");
        OpenAiChatModel model = new OpenAiChatModel(base + "/v1", "m", "secret-key", Duration.ofSeconds(5),
                GenerationSettings.defaults());

        ChatModel.ModelException error = assertThrows(ChatModel.ModelException.class,
                () -> model.complete(conversation(), TOOLS));

        assertTrue(error.getMessage().contains("HTTP 401"));
        assertFalse(error.getMessage().contains("secret-key"));
    }
}
