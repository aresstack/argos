package com.aresstack.argos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Persistent browser profiles per caller workspace: a ChatGPT conversation or an MCP transport session, as
 * {@link McpCallerResolver} identifies it (never by a tool argument, never by {@code openai/subject}). Like AskAI's
 * {@code ResearchBotSessionDirectory}, a missing identity is an error: there is no shared default profile and
 * no fallback to another workspace.
 * <p>
 * Like AskAI's research plugin ({@code ResearchAgentSessionFactory}: one workspace directory per chat, handed to
 * the browser at start), each workspace owns one directory, {@code <root>/<key>/profile}, which is the
 * Camoufox profile its browser starts with; {@code <key>} is a truncated SHA-256 of the identity, so
 * neither the id nor anything derived from model input becomes a path. Additional user contexts are WebDriver
 * BiDi user contexts inside that browser; {@code <root>/<key>/contexts.properties} lists them and the
 * active one, and {@code <root>/<key>/contexts/<id>.cookies.json} keeps each one's cookies across restarts.
 */
public final class ChatProfiles {
    public static final String SESSION_META_KEY = "openai/session";
    /** Directory prefix of workspaces that belong to one MCP transport session. */
    public static final String TRANSPORT_PREFIX = "s-";
    static final String START_CONTEXT = "start";
    private static final Pattern CONTEXT_ID = Pattern.compile("start|context-[1-9][0-9]{0,3}");
    private static final Pattern TRANSPORT_KEY = Pattern.compile(TRANSPORT_PREFIX + "[0-9a-f]{32}");
    private static final int MAX_CONTEXTS = 20;

    private final Path root;

    public ChatProfiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * A workspace as Argos keys it: the hashed directory key plus a short form for logs and answers, where the
     * identity came from ({@code chatgpt} or {@code mcp-session}) and the client's sanitized name for diagnostics.
     */
    public record Chat(String key, String source, String client) {
        /** At most this much of the hash appears in logs or answers. */
        public String shortKey() {
            return key.substring(0, 10);
        }
    }

    /** One context of a workspace: its id, where its cookies are kept (null for start), whether active. */
    public record Context(String id, Path cookies, boolean active) { }

    /** The workspace's browser profile directory, handed to Camoufox at start. */
    public Path profile(Chat chat) throws IOException {
        Path profile = root.resolve(chat.key()).resolve("profile");
        Files.createDirectories(profile);
        return profile;
    }

    /** Thrown when a call cannot be tied to a workspace; the message is safe for the model. */
    public static final class MissingChatIdentity extends IllegalStateException {
        MissingChatIdentity(String message) {
            super(message);
        }
    }

    /** The key of a ChatGPT conversation; unchanged, so existing profiles keep their directory. */
    static String hash(String session) {
        return digest("argos-chat-profile\u0000" + session);
    }

    /** The key of one MCP transport session of one verified principal; its own namespace and prefix. */
    static String transportKey(String principal, String sessionId) {
        return TRANSPORT_PREFIX + digest("argos-mcp-session\u0000" + principal + "\u0000" + sessionId);
    }

    private static String digest(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Deletes all transport-session workspaces. Called once at startup: MCP sessions live only in the memory of
     * the running server, so after a restart no client can ever reach these workspaces again. ChatGPT
     * conversation profiles are never touched.
     *
     * @return how many workspaces were removed
     */
    public int removeTransportWorkspaces() throws IOException {
        if (!Files.isDirectory(root)) return 0;
        List<Path> stale;
        try (var children = Files.list(root)) {
            stale = children.filter(Files::isDirectory)
                    .filter(dir -> TRANSPORT_KEY.matcher(dir.getFileName().toString()).matches()).toList();
        }
        for (Path workspace : stale) deleteTree(workspace);
        return stale.size();
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** The active context; on first access the workspace's start profile is created. */
    public synchronized Context active(Chat chat) throws IOException {
        State state = load(chat);
        if (state.contexts.isEmpty()) {
            state.contexts.add(START_CONTEXT);
            state.active = START_CONTEXT;
            save(chat, state);
        }
        return context(chat, state, state.active);
    }

    /** All contexts of this workspace, in creation order; another workspace's are never visible. */
    public synchronized List<Context> list(Chat chat) throws IOException {
        active(chat);
        State state = load(chat);
        List<Context> contexts = new ArrayList<>();
        for (String id : state.contexts) contexts.add(context(chat, state, id));
        return contexts;
    }

    /** A new, empty user context; it becomes active at once. */
    public synchronized Context create(Chat chat) throws IOException {
        active(chat);
        State state = load(chat);
        if (state.contexts.size() >= MAX_CONTEXTS) {
            throw new IllegalStateException("This workspace already has " + MAX_CONTEXTS + " contexts.");
        }
        int number = 2;
        while (state.contexts.contains("context-" + number)) number++;
        String id = "context-" + number;
        state.contexts.add(id);
        state.active = id;
        save(chat, state);
        return context(chat, state, id);
    }

    /** Activates an existing context of THIS workspace. Unknown ids (or another workspace's) are refused. */
    public synchronized Context select(Chat chat, String id) throws IOException {
        active(chat);
        State state = load(chat);
        String wanted = id == null ? "" : id.strip();
        if (!CONTEXT_ID.matcher(wanted).matches() || !state.contexts.contains(wanted)) {
            throw new IllegalArgumentException("Unknown context \"" + abbreviate(wanted)
                    + "\". Use browser_contexts to list this workspace's contexts.");
        }
        state.active = wanted;
        save(chat, state);
        return context(chat, state, wanted);
    }

    private Context context(Chat chat, State state, String id) throws IOException {
        Path cookies = START_CONTEXT.equals(id) ? null
                : root.resolve(chat.key()).resolve("contexts").resolve(id + ".cookies.json");
        return new Context(id, cookies, id.equals(state.active));
    }

    private static final class State {
        final List<String> contexts = new ArrayList<>();
        String active;
    }

    private Path stateFile(Chat chat) {
        return root.resolve(chat.key()).resolve("contexts.properties");
    }

    private State load(Chat chat) throws IOException {
        State state = new State();
        Path file = stateFile(chat);
        if (!Files.isRegularFile(file)) return state;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (String id : properties.getProperty("contexts", "").split(",")) {
            if (CONTEXT_ID.matcher(id.strip()).matches()) state.contexts.add(id.strip());
        }
        String active = properties.getProperty("active", "").strip();
        state.active = state.contexts.contains(active) ? active
                : state.contexts.isEmpty() ? null : state.contexts.get(0);
        return state;
    }

    private void save(Chat chat, State state) throws IOException {
        Path file = stateFile(chat);
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty("contexts", String.join(",", state.contexts));
        properties.setProperty("active", state.active);
        Path temporary = file.resolveSibling("contexts.properties.tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "Argos browser contexts of one workspace");
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }
}
