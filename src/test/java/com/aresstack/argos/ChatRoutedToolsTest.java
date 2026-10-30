package com.aresstack.argos;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routing by _meta["openai/session"] (ChatGPT) and by MCP transport session plus principal (every other client),
 * with fake tool stacks instead of Camoufox.
 */
class ChatRoutedToolsTest {

    @TempDir
    Path root;

    private final List<Path> started = new ArrayList<>();
    private final AtomicInteger closed = new AtomicInteger();

    /**
     * A fake browser per profile: "whoami" answers profile and user context, "set"/"get" keep per-context
     * cookies (and write them to the context's cookie file, as CamoufoxBrowser does).
     */
    private ChatRoutedTools.Workspace fake(Path profile) {
        started.add(profile);
        Map<String, String> cookies = new HashMap<>();
        String[] active = {"start"};
        Path[] file = {null};
        return new ChatRoutedTools.Workspace(List.of(
                ArgosTools.textTool("whoami", "", Map.of(), List.of(), args -> profile + "|" + active[0]),
                ArgosTools.textTool("set", "", Map.of(), List.of(), args -> {
                    cookies.put(active[0], (String) args.get("value"));
                    if (file[0] != null) {
                        try {
                            Files.createDirectories(file[0].getParent());
                            Files.writeString(file[0], (String) args.get("value"));
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return "ok";
                }),
                ArgosTools.textTool("get", "", Map.of(), List.of(), args -> {
                    if (!cookies.containsKey(active[0]) && file[0] != null && Files.isRegularFile(file[0])) {
                        try {
                            cookies.put(active[0], Files.readString(file[0]));
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return String.valueOf(cookies.get(active[0]));
                })),
                (id, cookieFile) -> {
                    active[0] = id;
                    file[0] = cookieFile;
                },
                closed::incrementAndGet);
    }

    /** The transport session and verified principal of the next calls, as the MCP SDK would deliver them. */
    private String transportSession;
    private String principal = "sub:issuer\u0000angelo";

    private ChatRoutedTools routed(int max) {
        return new ChatRoutedTools(new ChatProfiles(root), (exchange, request) -> McpCallerResolver.resolve(
                request.meta(), transportSession, principal, "claude-ai"), this::fake, max, java.time.Duration.ZERO);
    }

    private Map<String, SyncToolSpecification> tools(ChatRoutedTools routed) {
        Map<String, SyncToolSpecification> byName = new HashMap<>();
        routed.specifications(fake(root.resolve("template")).tools(), true)
                .forEach(tool -> byName.put(tool.tool().name(), tool));
        started.clear();
        return byName;
    }

    private static CallToolResult call(Map<String, SyncToolSpecification> tools, String name, String session,
                                       Map<String, Object> args) {
        Map<String, Object> meta = session == null ? null : Map.of(ChatProfiles.SESSION_META_KEY, session,
                "openai/subject", "same-user");
        return tools.get(name).callHandler().apply(null, new CallToolRequest(name, args, meta));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void sameConversationKeepsItsProfileDifferentConversationsAreSeparated() {
        Map<String, SyncToolSpecification> tools = tools(routed(4));
        String a1 = text(call(tools, "whoami", "conv-A", Map.of()));
        String a2 = text(call(tools, "whoami", "conv-A", Map.of()));
        String b = text(call(tools, "whoami", "conv-B", Map.of()));
        assertEquals(a1, a2);
        assertNotEquals(a1, b);
        assertTrue(a1.startsWith(root.toString()));
        assertTrue(a1.endsWith("profile|start"));
        assertFalse(a1.contains("conv-A"), "the conversation id must not appear in the path");
        call(tools, "set", "conv-A", Map.of("value", "login-A"));
        assertEquals("null", text(call(tools, "get", "conv-B", Map.of())));
        assertEquals("login-A", text(call(tools, "get", "conv-A", Map.of())));
    }

    @Test
    void callWithoutAnyIdentityIsRefusedWithoutStartingAnyBrowser() {
        Map<String, SyncToolSpecification> tools = tools(routed(4));
        CallToolResult result = call(tools, "whoami", null, Map.of());
        assertTrue(result.isError());
        assertTrue(text(result).contains("no shared default profile"));
        CallToolResult blank = call(tools, "browser_contexts", " ", Map.of());
        assertTrue(blank.isError());
        assertTrue(started.isEmpty());
    }

    @Test
    void argumentsCannotChooseTheConversation() {
        Map<String, SyncToolSpecification> tools = tools(routed(4));
        String a = text(call(tools, "whoami", "conv-A", Map.of()));
        String spoofed = text(call(tools, "whoami", "conv-B", Map.of("chat", "conv-A", "session", "conv-A")));
        assertNotEquals(a, spoofed);
    }

    @Test
    void additionalContextIsIsolatedSelectableAndPersistent() {
        ChatRoutedTools first = routed(4);
        Map<String, SyncToolSpecification> tools = tools(first);
        call(tools, "set", "conv-A", Map.of("value", "start-login"));
        String start = text(call(tools, "whoami", "conv-A", Map.of()));
        assertEquals("Created and activated context context-2.", text(call(tools, "browser_context_new", "conv-A", Map.of())));
        String second = text(call(tools, "whoami", "conv-A", Map.of()));
        assertNotEquals(start, second);
        assertEquals(start.replace("|start", "|context-2"), second, "same browser profile, other user context");
        assertEquals("null", text(call(tools, "get", "conv-A", Map.of())));
        call(tools, "set", "conv-A", Map.of("value", "second-login"));
        assertEquals("  start\n* context-2 (active)", text(call(tools, "browser_contexts", "conv-A", Map.of())));
        assertTrue(text(call(tools, "whoami", "conv-B", Map.of())).endsWith("|start"));

        // Another conversation neither sees nor selects conv-A's context-2.
        assertEquals("* start (active)", text(call(tools, "browser_contexts", "conv-B", Map.of())));
        assertTrue(call(tools, "browser_context_select", "conv-B", Map.of("context", "context-2")).isError());
        assertTrue(call(tools, "browser_context_select", "conv-A", Map.of("context", "../x")).isError());

        // A restarted server finds the same contexts and the same active one.
        Map<String, SyncToolSpecification> restarted = tools(routed(4));
        assertEquals(second, text(call(restarted, "whoami", "conv-A", Map.of())));
        assertEquals("second-login", text(call(restarted, "get", "conv-A", Map.of())));
        call(restarted, "browser_context_select", "conv-A", Map.of("context", "start"));
        assertEquals(start, text(call(restarted, "whoami", "conv-A", Map.of())));
    }

    @Test
    void idleBrowsersAboveTheLimitAreClosed() throws Exception {
        Map<String, SyncToolSpecification> tools = tools(routed(1));
        call(tools, "whoami", "conv-A", Map.of());
        call(tools, "whoami", "conv-B", Map.of());
        for (int i = 0; i < 50 && closed.get() == 0; i++) Thread.sleep(20);
        assertEquals(1, closed.get());
        assertTrue(Files.isDirectory(started.get(0)), "the profile stays on disk");
    }

    @Test
    void browsersUnusedForTheIdleTimeAreStopped() throws Exception {
        ChatRoutedTools routed = new ChatRoutedTools(new ChatProfiles(root), this::fake, 4,
                java.time.Duration.ofMillis(100));
        Map<String, SyncToolSpecification> tools = tools(routed);
        call(tools, "whoami", "conv-A", Map.of());
        for (int i = 0; i < 60 && closed.get() == 0; i++) Thread.sleep(100);
        assertEquals(1, closed.get());
        routed.close();
    }

    @Test
    void chatKeyIsAStableHash() {
        ChatProfiles.Chat chat = McpCallerResolver.resolve(Map.of("openai/session", "x"), null, null, null);
        assertEquals(chat, McpCallerResolver.resolve(Map.of("openai/session", " x "), null, null, null));
        // The key existing ChatGPT profile directories were created with: they must not be orphaned.
        assertEquals("da0ee7af19fd421953bf538a0a58ad75", chat.key());
        assertEquals("chatgpt", chat.source());
        assertThrows(ChatProfiles.MissingChatIdentity.class,
                () -> McpCallerResolver.resolve(Map.of("openai/subject", "user"), null, "p", null));
    }

    @Test
    void clientWithoutConversationIdGetsOneWorkspacePerTransportSession() {
        Map<String, SyncToolSpecification> tools = tools(routed(4));
        transportSession = "session-1";
        String first = text(call(tools, "whoami", null, Map.of()));
        assertEquals(first, text(call(tools, "whoami", null, Map.of())), "same session, same workspace");
        assertTrue(first.startsWith(root.resolve(ChatProfiles.TRANSPORT_PREFIX).toString()));
        assertFalse(first.contains("session-1"), "the session id must not appear in the path");
        call(tools, "set", null, Map.of("value", "login-1"));

        transportSession = "session-2"; // a second Claude connection, or the first one after a reconnect
        String second = text(call(tools, "whoami", null, Map.of()));
        assertNotEquals(first, second);
        assertEquals("null", text(call(tools, "get", null, Map.of())), "a new session starts empty");

        transportSession = "session-1";
        assertEquals("login-1", text(call(tools, "get", null, Map.of())));
    }

    @Test
    void transportWorkspacesAreBoundToThePrincipalAndNeverMeetChatGptProfiles() {
        Map<String, SyncToolSpecification> tools = tools(routed(4));
        transportSession = "same-id";
        String angelo = text(call(tools, "whoami", null, Map.of()));
        principal = "sub:issuer\u0000someone-else";
        String other = text(call(tools, "whoami", null, Map.of()));
        assertNotEquals(angelo, other, "a known session id does not open another principal's workspace");

        // ChatGPT on the same principal and even on the same transport session id: never the same profile.
        String chatGpt = text(call(tools, "whoami", "same-id", Map.of()));
        assertNotEquals(angelo, chatGpt);
        assertNotEquals(other, chatGpt);
        assertFalse(Path.of(chatGpt.split("\\|")[0]).getParent().getFileName().toString()
                .startsWith(ChatProfiles.TRANSPORT_PREFIX));
    }

    @Test
    void transportSessionWithoutVerifiedPrincipalIsRefused() {
        Map<String, SyncToolSpecification> tools = tools(routed(4));
        transportSession = "session-1";
        principal = null;
        CallToolResult result = call(tools, "whoami", null, Map.of());
        assertTrue(result.isError());
        assertTrue(text(result).contains("no verified principal"));
        assertTrue(call(tools, "browser_contexts", null, Map.of()).isError());
        assertTrue(started.isEmpty());
    }

    @Test
    void contextToolsUseTheSameResolverForTransportSessions() {
        Map<String, SyncToolSpecification> tools = tools(routed(4));
        transportSession = "session-1";
        assertEquals("Created and activated context context-2.",
                text(call(tools, "browser_context_new", null, Map.of())));
        assertTrue(text(call(tools, "whoami", null, Map.of())).endsWith("|context-2"));
        transportSession = "session-2";
        assertEquals("* start (active)", text(call(tools, "browser_contexts", null, Map.of())));
        assertTrue(call(tools, "browser_context_select", null, Map.of("context", "context-2")).isError());
    }

    @Test
    void endedTransportWorkspacesAreRemovedAtStartupChatGptProfilesStay() throws Exception {
        Map<String, SyncToolSpecification> tools = tools(routed(4));
        transportSession = "session-1";
        Path transport = Path.of(text(call(tools, "whoami", null, Map.of())).split("\\|")[0]).getParent();
        Path chatGpt = Path.of(text(call(tools, "whoami", "conv-A", Map.of())).split("\\|")[0]).getParent();
        Path unrelated = Files.createDirectories(root.resolve("s-not-a-key"));
        assertEquals(1, new ChatProfiles(root).removeTransportWorkspaces());
        assertFalse(Files.exists(transport));
        assertTrue(Files.isDirectory(chatGpt));
        assertTrue(Files.isDirectory(unrelated));
    }

    @Test
    void clientNameOnlyAppearsSanitized() {
        assertEquals("claude-ai", McpCallerResolver.label("claude-ai"));
        assertEquals("unknown", McpCallerResolver.label(null));
        assertEquals("a_b_c", McpCallerResolver.label("a b\nc"));
        assertEquals(32, McpCallerResolver.label("x".repeat(100)).length());
    }
}
