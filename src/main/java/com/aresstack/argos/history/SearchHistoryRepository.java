package com.aresstack.argos.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The canonical, durable history of ONE chat, the source of truth from which the Lucene index can always be
 * rebuilt:
 * <pre>
 * history/
 *   events.jsonl              search and page_visit events, one JSON object per line, chronological
 *   pages/&lt;snapshotId&gt;.json   readable content, links and metadata of one distinct page snapshot
 *   pages/&lt;snapshotId&gt;.html   the single-file HTML archive of that snapshot (only made by browser_archive_html)
 *   resources/&lt;resourceId&gt;.json  metadata of one distinct captured response body
 *   resources/&lt;resourceId&gt;.body  that body: masked UTF-8 text, or the received bytes of a binary resource
 *   capture-settings.json     this chat's overrides of the optional resource categories ({@link CaptureSettings})
 *   lucene/                   rebuildable index ({@link LuceneSearchHistoryIndex})
 *   exports/                  files written by search_history_export
 * </pre>
 * Nothing is read before the first operation; the event log is scanned once for its last sequence number.
 */
public final class SearchHistoryRepository {
    private static final Pattern SNAPSHOT_ID = Pattern.compile("[0-9a-f]{32}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;
    private final Path events;
    private final Path pages;
    private final Path resources;
    /** Bytes under resources/, -1 until counted once. */
    private long resourceBytes = -1;
    /** -1 until the event log was scanned. */
    private long lastSeq = -1;

    public SearchHistoryRepository(Path historyDirectory) {
        this.root = historyDirectory.toAbsolutePath().normalize();
        this.events = root.resolve("events.jsonl");
        this.pages = root.resolve("pages");
        this.resources = root.resolve("resources");
    }

    public Path root() {
        return root;
    }

    public Path luceneDirectory() {
        return root.resolve("lucene");
    }

    public Path exportsDirectory() {
        return root.resolve("exports");
    }

    // ------------------------------------------------------------------ hashes

    /** SHA-256 of the readable content (title and text, whitespace normalized); never includes a timestamp. */
    public static String contentHash(String title, String text) {
        String normalized = normalize(title) + "\n" + normalize(text);
        return sha256(normalized);
    }

    /** The snapshot of {@code url} with that content: same URL and content = same id = no new copy. */
    public static String snapshotId(String url, String contentHash) {
        return sha256((url == null ? "" : url) + "\n" + contentHash).substring(0, 32);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ------------------------------------------------------------------ events

    /** The sequence number of the newest event, 0 for an empty history. */
    public synchronized long lastSeq() throws IOException {
        if (lastSeq < 0) {
            long max = 0;
            if (Files.isRegularFile(events)) {
                repairTornLine();
                try (BufferedReader reader = Files.newBufferedReader(events, StandardCharsets.UTF_8)) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        JsonNode node = parseLine(line);
                        if (node != null) max = Math.max(max, node.path("seq").asLong(0));
                    }
                }
            }
            lastSeq = max;
        }
        return lastSeq;
    }

    public synchronized HistoryEvent.Search appendSearch(Instant at, String query, String provider, String contextId,
                                                         List<HistoryEvent.Result> results) throws IOException {
        HistoryEvent.Search event = new HistoryEvent.Search(lastSeq() + 1, at, query, provider, contextId, results);
        append(toJson(event));
        return event;
    }

    public synchronized HistoryEvent.PageVisit appendVisit(Instant at, String url, String requestedUrl, String title,
                                                           int status, String contextId, String snapshotId,
                                                           String contentHash, String source) throws IOException {
        HistoryEvent.PageVisit event = new HistoryEvent.PageVisit(lastSeq() + 1, at, url, requestedUrl, title,
                status, contextId, snapshotId, contentHash, source);
        append(toJson(event));
        return event;
    }

    public synchronized HistoryEvent.NetworkResource appendResource(Instant at, String url, String pageUrl, int status,
                                                                    String mimeType, String category,
                                                                    String resourceType, String contextId,
                                                                    StoredResource resource, boolean complete,
                                                                    String contentRange) throws IOException {
        HistoryEvent.NetworkResource event = new HistoryEvent.NetworkResource(lastSeq() + 1, at, url, pageUrl,
                status, mimeType, category, resourceType, contextId, resource.resourceId(), resource.contentHash(),
                resource.size(), complete, resource.masked(), contentRange);
        append(toJson(event));
        return event;
    }

    /**
     * Streams the events with {@code seq > afterSeq} in log order without loading the log into memory. Lines that
     * are not valid events (an interrupted write) are skipped.
     */
    public void forEach(long afterSeq, EventConsumer consumer) throws IOException {
        long until;
        synchronized (this) {
            until = lastSeq();
        }
        if (!Files.isRegularFile(events)) return;
        try (BufferedReader reader = Files.newBufferedReader(events, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                JsonNode node = parseLine(line);
                HistoryEvent event = node == null ? null : fromJson(node);
                if (event == null || event.seq() <= afterSeq) continue;
                if (event.seq() > until) break;
                consumer.accept(event);
            }
        }
    }

    /** Like {@link Consumer}, but may fail with an I/O error. */
    @FunctionalInterface
    public interface EventConsumer {
        void accept(HistoryEvent event) throws IOException;
    }

    private void append(ObjectNode node) throws IOException {
        Files.createDirectories(root);
        byte[] line = (JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(events, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(false);
        }
        lastSeq = node.path("seq").asLong();
    }

    /** A crash during a write can leave a line without its newline; the next event must not be glued to it. */
    private void repairTornLine() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(events.toFile(), "rw")) {
            long length = file.length();
            if (length == 0) return;
            file.seek(length - 1);
            if (file.read() != '\n') {
                file.seek(length);
                file.write('\n');
            }
        }
    }

    private static JsonNode parseLine(String line) {
        if (line.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(line);
            return node != null && node.isObject() && node.path("seq").asLong(0) > 0 ? node : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    static ObjectNode toJson(HistoryEvent event) {
        ObjectNode node = JSON.createObjectNode();
        node.put("seq", event.seq());
        switch (event) {
            case HistoryEvent.Search search -> {
                node.put("type", "search");
                node.put("at", search.at().toString());
                node.put("query", search.query());
                node.put("provider", search.provider());
                node.put("contextId", search.contextId());
                ArrayNode results = node.putArray("results");
                for (HistoryEvent.Result result : search.results()) {
                    ObjectNode item = results.addObject();
                    item.put("rank", result.rank());
                    item.put("title", result.title());
                    item.put("url", result.url());
                    item.put("snippet", result.snippet());
                }
            }
            case HistoryEvent.PageVisit visit -> {
                node.put("type", "page_visit");
                node.put("at", visit.at().toString());
                node.put("url", visit.url());
                node.put("requestedUrl", visit.requestedUrl());
                node.put("title", visit.title());
                node.put("status", visit.status());
                node.put("contextId", visit.contextId());
                node.put("snapshotId", visit.snapshotId());
                node.put("contentHash", visit.contentHash());
                node.put("source", visit.source());
            }
            case HistoryEvent.NetworkResource resource -> {
                node.put("type", "network_resource");
                node.put("at", resource.at().toString());
                node.put("url", resource.url());
                node.put("pageUrl", resource.pageUrl());
                node.put("status", resource.status());
                node.put("mimeType", resource.mimeType());
                node.put("category", resource.category());
                node.put("resourceType", resource.resourceType());
                node.put("contextId", resource.contextId());
                node.put("resourceId", resource.resourceId());
                node.put("contentHash", resource.contentHash());
                node.put("size", resource.size());
                node.put("complete", resource.complete());
                node.put("masked", resource.masked());
                node.put("contentRange", resource.contentRange());
            }
        }
        return node;
    }

    static HistoryEvent fromJson(JsonNode node) {
        try {
            long seq = node.path("seq").asLong();
            Instant at = Instant.parse(node.path("at").asText());
            String context = text(node, "contextId");
            switch (node.path("type").asText()) {
                case "search" -> {
                    List<HistoryEvent.Result> results = new ArrayList<>();
                    for (JsonNode item : node.path("results")) {
                        results.add(new HistoryEvent.Result(item.path("rank").asInt(), text(item, "title"),
                                text(item, "url"), text(item, "snippet")));
                    }
                    return new HistoryEvent.Search(seq, at, text(node, "query"), text(node, "provider"), context,
                            results);
                }
                case "page_visit" -> {
                    String snapshot = text(node, "snapshotId");
                    if (snapshot == null || !SNAPSHOT_ID.matcher(snapshot).matches()) return null;
                    return new HistoryEvent.PageVisit(seq, at, text(node, "url"), text(node, "requestedUrl"),
                            text(node, "title"), node.path("status").asInt(0), context, snapshot,
                            text(node, "contentHash"), text(node, "source"));
                }
                case "network_resource" -> {
                    String resource = text(node, "resourceId");
                    if (resource == null || !SNAPSHOT_ID.matcher(resource).matches()) return null;
                    return new HistoryEvent.NetworkResource(seq, at, text(node, "url"), text(node, "pageUrl"),
                            node.path("status").asInt(0), text(node, "mimeType"), text(node, "category"),
                            text(node, "resourceType"), context, resource, text(node, "contentHash"),
                            node.path("size").asLong(0), node.path("complete").asBoolean(false),
                            node.path("masked").asBoolean(false), text(node, "contentRange"));
                }
                default -> {
                    // Unknown types (from a newer Argos) are skipped, never fatal.
                    return null;
                }
            }
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    // ------------------------------------------------------------------ page snapshots

    public boolean hasPage(String snapshotId) {
        return Files.isRegularFile(pageFile(snapshotId, ".json"));
    }

    public Optional<StoredPage> page(String snapshotId) throws IOException {
        Path file = pageFile(snapshotId, ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        JsonNode node = JSON.readTree(file.toFile());
        List<String> links = new ArrayList<>();
        node.path("links").forEach(link -> links.add(link.asText()));
        return Optional.of(new StoredPage(snapshotId, text(node, "url"), text(node, "title"),
                node.path("status").asInt(0), text(node, "contentHash"), Instant.parse(node.path("capturedAt").asText()),
                text(node, "content"), links, text(node, "htmlNote")));
    }

    public void savePage(StoredPage page) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("snapshotId", page.snapshotId());
        node.put("url", page.url());
        node.put("title", page.title());
        node.put("status", page.status());
        node.put("contentHash", page.contentHash());
        node.put("capturedAt", page.capturedAt().toString());
        node.put("content", page.content());
        ArrayNode links = node.putArray("links");
        page.links().forEach(links::add);
        node.put("htmlNote", page.htmlNote());
        writeAtomically(pageFile(page.snapshotId(), ".json"), JSON.writeValueAsBytes(node));
    }

    public boolean hasHtml(String snapshotId) {
        return Files.isRegularFile(pageFile(snapshotId, ".html"));
    }

    /** The single-file archive of a snapshot; exists only when {@link #hasHtml} is true. */
    public Path htmlFile(String snapshotId) {
        return pageFile(snapshotId, ".html");
    }

    public void saveHtml(String snapshotId, String html) throws IOException {
        writeAtomically(pageFile(snapshotId, ".html"), html.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ network resources

    /** The id of a stored body: derived from its bytes, so identical bodies share one id. */
    public static String resourceId(String contentHash) {
        return contentHash.substring(0, 32);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public boolean hasResource(String resourceId) {
        return Files.isRegularFile(resourceFile(resourceId, ".json"))
                && Files.isRegularFile(resourceFile(resourceId, ".body"));
    }

    public Optional<StoredResource> resource(String resourceId) throws IOException {
        Path file = resourceFile(resourceId, ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        JsonNode node = JSON.readTree(file.toFile());
        return Optional.of(new StoredResource(resourceId, text(node, "mimeType"), text(node, "category"),
                text(node, "textKind"), text(node, "contentHash"), node.path("size").asLong(0),
                Instant.parse(node.path("storedAt").asText()), node.path("masked").asBoolean(false)));
    }

    /** The stored body; exists only when {@link #hasResource} is true. */
    public Path resourceBody(String resourceId) {
        return resourceFile(resourceId, ".body");
    }

    /** Stores body and metadata (body first, so metadata never points at a missing body). */
    public synchronized void saveResource(StoredResource resource, byte[] body) throws IOException {
        long before = resourceBytes();
        writeAtomically(resourceFile(resource.resourceId(), ".body"), body);
        ObjectNode node = JSON.createObjectNode();
        node.put("resourceId", resource.resourceId());
        node.put("mimeType", resource.mimeType());
        node.put("category", resource.category());
        node.put("textKind", resource.textKind());
        node.put("contentHash", resource.contentHash());
        node.put("size", resource.size());
        node.put("storedAt", resource.storedAt().toString());
        node.put("masked", resource.masked());
        byte[] meta = JSON.writeValueAsBytes(node);
        writeAtomically(resourceFile(resource.resourceId(), ".json"), meta);
        resourceBytes = before + body.length + meta.length;
    }

    /** Bytes stored under resources/ (counted once, then kept up to date). */
    public synchronized long resourceBytes() throws IOException {
        if (resourceBytes < 0) {
            long total = 0;
            if (Files.isDirectory(resources)) {
                try (var files = Files.list(resources)) {
                    for (Path file : files.toList()) {
                        if (Files.isRegularFile(file)) total += Files.size(file);
                    }
                }
            }
            resourceBytes = total;
        }
        return resourceBytes;
    }

    private Path resourceFile(String resourceId, String extension) {
        if (resourceId == null || !SNAPSHOT_ID.matcher(resourceId).matches()) {
            throw new IllegalArgumentException("Invalid resource id");
        }
        return resources.resolve(resourceId + extension);
    }

    private Path pageFile(String snapshotId, String extension) {
        if (snapshotId == null || !SNAPSHOT_ID.matcher(snapshotId).matches()) {
            throw new IllegalArgumentException("Invalid snapshot id");
        }
        return pages.resolve(snapshotId + extension);
    }

    private static void writeAtomically(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, content);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
