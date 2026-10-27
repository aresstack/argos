package com.aresstack.argos;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Routes every {@code tools/call} to the browser of the calling ChatGPT conversation's active context.
 * <p>
 * No second browser architecture: each conversation's profile directory gets the unchanged Argos tool stack
 * (one {@link CamoufoxBrowser} = one Camoufox process started with that profile, plus its WebSession and
 * documents), built by the same factory the single-profile server uses. This class only picks which stack
 * answers and which BiDi user context it uses. Idle stacks beyond {@code maxBrowsers} are closed (least recently used first); their profile stays on
 * disk and the browser starts again with the next call.
 */
public final class ChatRoutedTools implements AutoCloseable {

    /**
     * The tools of one conversation's browser, how to select its user context (id, cookie file) and what to close
     * when the browser is stopped.
     */
    public record Workspace(List<SyncToolSpecification> tools, BiConsumer<String, Path> contexts,
                            AutoCloseable resources) { }

    private final ChatProfiles profiles;
    private final Function<Path, Workspace> factory;
    private final int maxBrowsers;
    /** Access order = least recently used first. */
    private final LinkedHashMap<Path, Entry> open = new LinkedHashMap<>(16, 0.75f, true);

    private static final class Entry {
        final Map<String, SyncToolSpecification> tools = new LinkedHashMap<>();
        final AutoCloseable resources;
        final BiConsumer<String, Path> contexts;
        int calls;

        Entry(Workspace workspace) {
            workspace.tools().forEach(tool -> tools.put(tool.tool().name(), tool));
            this.resources = workspace.resources();
            this.contexts = workspace.contexts();
        }
    }

    public ChatRoutedTools(ChatProfiles profiles, Function<Path, Workspace> factory, int maxBrowsers) {
        this.profiles = profiles;
        this.factory = factory;
        this.maxBrowsers = Math.max(1, maxBrowsers);
    }

    /**
     * The published tools: the template's tools, each routed to the caller's active context, and (in the
     * expert profile) browser_context_new, browser_contexts and browser_context_select.
     */
    public List<SyncToolSpecification> specifications(List<SyncToolSpecification> template, boolean contextTools) {
        List<SyncToolSpecification> routed = new ArrayList<>();
        for (SyncToolSpecification spec : template) {
            String name = spec.tool().name();
            routed.add(SyncToolSpecification.builder().tool(spec.tool())
                    .callHandler((exchange, request) -> route(name, exchange, request)).build());
        }
        if (contextTools) routed.addAll(contextTools());
        return List.copyOf(routed);
    }

    private CallToolResult route(String name, io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                                 CallToolRequest request) {
        ChatProfiles.Chat chat;
        ChatProfiles.Context context;
        Path profile;
        try {
            chat = ChatProfiles.chatOf(request.meta());
            context = profiles.active(chat);
            profile = profiles.profile(chat);
        } catch (Exception error) {
            log("refused " + name + ": " + (error instanceof ChatProfiles.MissingChatIdentity
                    ? "no openai/session" : error.getClass().getSimpleName()));
            return ArgosTools.failure(error);
        }
        log(name + " chat=" + chat.shortKey() + " context=" + context.id());
        Entry entry = acquire(profile);
        try {
            entry.contexts.accept(context.id(), context.cookies());
            SyncToolSpecification tool = entry.tools.get(name);
            if (tool == null) return ArgosTools.failure(new IllegalStateException("Unknown tool " + name));
            return tool.callHandler().apply(exchange, request);
        } finally {
            release(entry);
        }
    }

    private synchronized Entry acquire(Path profile) {
        Entry entry = open.get(profile);
        if (entry == null) {
            entry = new Entry(factory.apply(profile));
            open.put(profile, entry);
        }
        entry.calls++;
        evictIdle();
        return entry;
    }

    private void release(Entry entry) {
        synchronized (this) {
            entry.calls--;
        }
        evictIdle();
    }

    /** Closes the least recently used idle browsers above the limit; a browser in use is never stopped. */
    private synchronized List<AutoCloseable> takeEvicted() {
        List<AutoCloseable> evicted = new ArrayList<>();
        var iterator = open.entrySet().iterator();
        int excess = open.size() - maxBrowsers;
        while (excess > 0 && iterator.hasNext()) {
            Entry candidate = iterator.next().getValue();
            if (candidate.calls > 0) continue;
            iterator.remove();
            evicted.add(candidate.resources);
            excess--;
        }
        return evicted;
    }

    private void evictIdle() {
        List<AutoCloseable> evicted = takeEvicted();
        if (evicted.isEmpty()) return;
        // Closing waits for Camoufox to exit; the calling tool must not wait for that.
        Thread.ofVirtual().name("argos-profile-close").start(() -> evicted.forEach(ChatRoutedTools::closeQuietly));
    }

    private List<SyncToolSpecification> contextTools() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(contextTool("browser_context_new",
                "Create a new, empty browser user context for this conversation and switch to it. It shares no "
                        + "cookies, storage or logins with other contexts; its cookies are kept. Returns its id.",
                Map.of(), List.of(), (chat, args) -> {
                    ChatProfiles.Context created = profiles.create(chat);
                    log("context_new chat=" + chat.shortKey() + " context=" + created.id());
                    return "Created and activated context " + created.id() + ".";
                }));
        tools.add(contextTool("browser_contexts",
                "List the browser user contexts of this conversation; the active one is marked.",
                Map.of(), List.of(), (chat, args) -> {
                    StringBuilder text = new StringBuilder();
                    for (ChatProfiles.Context context : profiles.list(chat)) {
                        text.append(context.active() ? "* " : "  ").append(context.id())
                                .append(context.active() ? " (active)" : "").append('\n');
                    }
                    return text.toString().stripTrailing();
                }));
        tools.add(contextTool("browser_context_select",
                "Switch to another browser user context of this conversation (see browser_contexts). "
                        + "Example: {\"context\": \"context-2\"}",
                Map.of("context", ArgosTools.field("string", "Context id from browser_contexts")),
                List.of("context"), (chat, args) -> {
                    ChatProfiles.Context selected = profiles.select(chat, ArgosTools.string(args, "context"));
                    log("context_select chat=" + chat.shortKey() + " context=" + selected.id());
                    return "Active context: " + selected.id() + ".";
                }));
        return tools;
    }

    @FunctionalInterface
    private interface ContextHandler {
        String apply(ChatProfiles.Chat chat, Map<String, Object> arguments) throws Exception;
    }

    private static SyncToolSpecification contextTool(String name, String description, Map<String, Object> properties,
                                                      List<String> required, ContextHandler handler) {
        return SyncToolSpecification.builder()
                .tool(io.modelcontextprotocol.spec.McpSchema.Tool.builder().name(name).description(description)
                        .inputSchema(ArgosTools.schema(properties, required)).build())
                .callHandler((exchange, request) -> {
                    try {
                        ChatProfiles.Chat chat = ChatProfiles.chatOf(request.meta());
                        String answer = handler.apply(chat,
                                request.arguments() == null ? Map.of() : request.arguments());
                        return CallToolResult.builder().addTextContent(answer).build();
                    } catch (Exception error) {
                        return ArgosTools.failure(error);
                    }
                }).build();
    }

    /** Diagnostics: only the shortened hash of the conversation id, never the id, a path or page data. */
    private static void log(String line) {
        System.err.println("[argos-profiles] " + line);
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception error) {
            log("closing a profile browser failed: " + error.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        List<AutoCloseable> all;
        synchronized (this) {
            all = new ArrayList<>();
            open.values().forEach(entry -> all.add(entry.resources));
            open.clear();
        }
        all.forEach(ChatRoutedTools::closeQuietly);
    }
}
