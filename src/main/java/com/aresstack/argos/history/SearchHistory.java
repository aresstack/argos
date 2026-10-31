package com.aresstack.argos.history;

import com.aresstack.argos.network.SecretMasker;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Search History of ONE chat: records search events and page visits and searches them. It knows neither the
 * browser nor Lucene types. Every user context of the chat writes into the same history; the context id is kept as
 * metadata only.
 * <p>
 * A record is first written to the canonical {@link SearchHistoryRepository} (synchronously, so it survives any
 * index failure), then indexed in the background. Nothing is opened before the first record or search of this
 * chat, and no other chat's data is ever touched.
 */
public final class SearchHistory {
    /** One background indexer for all chats, with a bounded queue; a chat has at most one task queued. */
    private static final ThreadPoolExecutor INDEXER = indexer();
    /** Indexing tasks submitted and not yet finished, of all chats. */
    private static final java.util.concurrent.atomic.AtomicInteger PENDING = new java.util.concurrent.atomic.AtomicInteger();

    /** Upper bound of all stored resource bodies of one chat; beyond it new bodies are skipped (logged). */
    public static final long MAX_CHAT_RESOURCE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final SecretMasker MASKER = new SecretMasker();

    private final SearchHistoryRepository repository;
    private final LuceneSearchHistoryIndex index;
    private final CaptureSettings captureSettings;
    private final AtomicBoolean indexQueued = new AtomicBoolean();
    /** The page recorded last, so re-reading the same page (web_read part 2) records no new visit. */
    private String lastUrl;
    private String lastSnapshot;

    public SearchHistory(Path historyDirectory) {
        this(historyDirectory, Map.of());
    }

    /** @param captureDefaults the server's SEARCH_HISTORY_CAPTURE_* values, the start of every chat */
    public SearchHistory(Path historyDirectory, Map<ResourceCategory, Boolean> captureDefaults) {
        this.repository = new SearchHistoryRepository(historyDirectory);
        this.index = new LuceneSearchHistoryIndex(repository.luceneDirectory());
        this.captureSettings = new CaptureSettings(repository.root(), captureDefaults);
    }

    /** This chat's optional resource categories. */
    public CaptureSettings captureSettings() {
        return captureSettings;
    }

    public SearchHistoryRepository repository() {
        return repository;
    }

    /** A page as a reading tool saw it: final URL, readable text and absolute links. */
    public record PageRead(String url, String requestedUrl, String title, int status, String text,
                           List<String> links) {
        public PageRead {
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    /**
     * A response body the browser already received (read over the protocol, never fetched again).
     *
     * @param url           masked URL of the response
     * @param contentLength announced length, null when unknown
     * @param encoded       a Content-Encoding was announced (the length is then not the body's length)
     */
    public record Received(String url, String pageUrl, int status, String contentType, String resourceType,
                           byte[] body, Long contentLength, String contentRange, boolean encoded) { }

    /** What a page record did. {@code visit} is null when a re-read of the same content recorded nothing. */
    public record Recorded(HistoryEvent.PageVisit visit, boolean newSnapshot, boolean htmlArchived) { }

    /**
     * One search result of {@link #search}. {@code type} is "page", "search" or "resource" (a captured network
     * response; {@code mimeType} and {@code category} are set only for those).
     */
    public record Found(String type, Instant at, String title, String url, String snippet, String contextId,
                        String mimeType, String category) {
        public Found(String type, Instant at, String title, String url, String snippet, String contextId) {
            this(type, at, title, url, snippet, contextId, null, null);
        }
    }

    /** Records one successful search call; identical queries are separate events. */
    public HistoryEvent.Search recordSearch(String query, String provider, String contextId,
                                            List<HistoryEvent.Result> results) throws IOException {
        List<HistoryEvent.Result> safe = new ArrayList<>();
        for (HistoryEvent.Result result : results) {
            safe.add(new HistoryEvent.Result(result.rank(), result.title(), ArchiveSanitizer.safeUrl(result.url()),
                    result.snippet()));
        }
        HistoryEvent.Search event = repository.appendSearch(now(), query, provider, contextId, safe);
        scheduleIndexing();
        return event;
    }

    /**
     * Records a page read. {@code newVisit} is true for an opening navigation (open, follow, back, navigate) and
     * false for re-reading the shown page (web_read parts, web_find, browser_snapshot): a re-read of the same URL
     * with unchanged content records nothing. The same URL with unchanged content reuses its snapshot; changed
     * content creates a new one. No HTML archive is made here: only {@link #archiveHtml} (browser_archive_html)
     * stores one.
     */
    public synchronized Recorded recordPage(PageRead page, String contextId, String source, boolean newVisit)
            throws IOException {
        String url = ArchiveSanitizer.safeUrl(page.url());
        String requested = page.requestedUrl() == null ? null : ArchiveSanitizer.safeUrl(page.requestedUrl());
        String hash = SearchHistoryRepository.contentHash(page.title(), page.text());
        String snapshotId = SearchHistoryRepository.snapshotId(url, hash);
        if (!newVisit && url.equals(lastUrl) && snapshotId.equals(lastSnapshot)) {
            return new Recorded(null, false, repository.hasHtml(snapshotId));
        }
        boolean newSnapshot = !repository.hasPage(snapshotId);
        Instant at = now();
        StoredPage stored = newSnapshot ? null : repository.page(snapshotId).orElse(null);
        if (stored == null) {
            List<String> links = new ArrayList<>();
            for (String link : page.links()) links.add(ArchiveSanitizer.safeUrl(link));
            stored = new StoredPage(snapshotId, url, page.title(), page.status(), hash, at, page.text(), links,
                    NO_ARCHIVE);
            repository.savePage(stored);
        }
        HistoryEvent.PageVisit visit = repository.appendVisit(at, url, requested, page.title(), page.status(),
                contextId, snapshotId, hash, source);
        lastUrl = url;
        lastSnapshot = snapshotId;
        scheduleIndexing();
        return new Recorded(visit, newSnapshot, repository.hasHtml(snapshotId));
    }

    static final String NO_ARCHIVE = "No single-file HTML archive: it is only made by an explicit "
            + "browser_archive_html call.";

    /** What an explicit archive stored: the snapshot it belongs to and the sanitized single-file HTML. */
    public record Archived(String snapshotId, String html) { }

    /**
     * The snapshot of exactly this page content, recorded first when it does not exist yet (no new visit when it
     * does). browser_archive_html calls it before the collector runs, so a failing collector never costs the text.
     */
    public synchronized String ensureSnapshot(PageRead page, String contextId) throws IOException {
        String url = ArchiveSanitizer.safeUrl(page.url());
        String snapshotId = SearchHistoryRepository.snapshotId(url,
                SearchHistoryRepository.contentHash(page.title(), page.text()));
        if (!repository.hasPage(snapshotId)) recordPage(page, contextId, "browser_archive_html", true);
        return snapshotId;
    }

    /**
     * Stores the single-file HTML of the shown page (browser_archive_html) with the page's snapshot, so later ZIP
     * exports use it instead of the text fallback.
     */
    public synchronized Archived archiveHtml(PageRead page, String contextId, String html) throws IOException {
        String snapshotId = ensureSnapshot(page, contextId);
        String sanitized = ArchiveSanitizer.sanitize(html, page.url());
        repository.saveHtml(snapshotId, sanitized);
        StoredPage stored = repository.page(snapshotId).orElse(null);
        if (stored != null && stored.htmlNote() != null) {
            repository.savePage(new StoredPage(stored.snapshotId(), stored.url(), stored.title(), stored.status(),
                    stored.contentHash(), stored.capturedAt(), stored.content(), stored.links(), null));
        }
        return new Archived(snapshotId, sanitized);
    }

    /**
     * Records one received response body when its category is enabled for this chat. Text is decoded with the
     * declared charset and masked ({@link ArchiveSanitizer} for HTML, {@link SecretMasker#body} for JSON and other
     * text); binary bodies are stored as received. Identical stored bytes share one resource; every observation is
     * its own event.
     *
     * @return the event, or null when the category is off, the body is empty or the chat's volume limit is reached
     */
    public HistoryEvent.NetworkResource recordResource(Received received, String contextId) throws IOException {
        ResourceCategory category = ResourceCategory.classify(received.contentType());
        if (!captureSettings.enabled(category) || received.body() == null || received.body().length == 0) {
            return null;
        }
        String mime = ResourceCategory.normalize(received.contentType());
        ResourceCategory.TextKind kind = ResourceCategory.textKind(received.contentType());
        String url = ArchiveSanitizer.safeUrl(received.url());
        byte[] stored = received.body();
        boolean masked = false;
        if (kind != ResourceCategory.TextKind.BINARY) {
            String text = new String(received.body(), charset(received.contentType()));
            String clean = kind == ResourceCategory.TextKind.HTML ? ArchiveSanitizer.sanitize(text, url)
                    : MASKER.body(text, mime);
            masked = !clean.equals(text);
            stored = clean.getBytes(StandardCharsets.UTF_8);
        }
        String hash = SearchHistoryRepository.sha256(stored);
        String resourceId = SearchHistoryRepository.resourceId(hash);
        boolean partial = received.status() == 206 || received.contentRange() != null;
        boolean complete = !partial && (received.contentLength() == null || received.encoded()
                || received.contentLength() == received.body().length);
        HistoryEvent.NetworkResource event;
        synchronized (this) {
            StoredResource resource = repository.hasResource(resourceId)
                    ? repository.resource(resourceId).orElse(null) : null;
            if (resource == null) {
                if (repository.resourceBytes() + stored.length > MAX_CHAT_RESOURCE_BYTES) {
                    System.err.println("[argos-history] resource volume limit of this chat reached; body skipped");
                    return null;
                }
                resource = new StoredResource(resourceId, mime.isEmpty() ? "" : mime, category.key(),
                        kind.name().toLowerCase(Locale.ROOT), hash, stored.length, now(), masked);
                repository.saveResource(resource, stored);
            }
            // The bytes are shared; MIME type, category and masking belong to THIS observation.
            event = repository.appendResource(now(), url, received.pageUrl() == null ? null
                            : ArchiveSanitizer.safeUrl(received.pageUrl()), received.status(), mime,
                    category.key(), received.resourceType(), contextId, resource, masked, complete,
                    received.contentRange());
        }
        scheduleIndexing();
        return event;
    }

    /** The searchable text of a stored resource (HTML as readable text); "" for binary resources. */
    String resourceText(StoredResource resource, int maxCharacters) throws IOException {
        if (!resource.textual()) return "";
        java.nio.file.Path body = repository.resourceBody(resource.resourceId());
        if (!java.nio.file.Files.isRegularFile(body)) return "";
        String text = java.nio.file.Files.readString(body, StandardCharsets.UTF_8);
        if ("html".equals(resource.textKind())) text = Jsoup.parse(text).text();
        return text.length() > maxCharacters ? text.substring(0, maxCharacters) : text;
    }

    private static Charset charset(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String trimmed = part.strip();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(trimmed.substring(8).replace("\"", "").strip());
                    } catch (RuntimeException unsupported) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Full-text search over this chat's history. Brings the chat's index up to date first (also after a failed
     * background run, or rebuilt when it is missing or damaged), so a search always sees every stored record.
     */
    public List<Found> search(String query, int limit) throws IOException {
        index.catchUp(repository);
        List<Found> found = new ArrayList<>();
        int wanted = Math.max(1, Math.min(50, limit));
        List<LuceneSearchHistoryIndex.Hit> hits = index.search(query, wanted * 2);
        Set<String> pageUrls = new HashSet<>();
        for (LuceneSearchHistoryIndex.Hit hit : hits) if ("page".equals(hit.type())) pageUrls.add(hit.url());
        for (LuceneSearchHistoryIndex.Hit hit : hits) {
            if (found.size() >= wanted) break;
            if ("page".equals(hit.type())) {
                String content = repository.page(hit.snapshotId()).map(StoredPage::content).orElse("");
                found.add(new Found("page", hit.at(), hit.title(), hit.url(), snippet(content, query),
                        hit.contextId()));
            } else if ("resource".equals(hit.type())) {
                StoredResource resource = repository.resource(hit.resourceId()).orElse(null);
                if (resource == null) continue;
                // The page's own HTML document is already a page hit: no second hit with the same text.
                if ("html".equals(resource.textKind()) && pageUrls.contains(hit.url())) continue;
                found.add(new Found("resource", hit.at(), hit.title(), hit.url(),
                        snippet(resourceText(resource, 200_000), query), hit.contextId(), resource.mimeType(),
                        resource.category()));
            } else {
                found.add(new Found("search", hit.at(), hit.title(), null, null, hit.contextId()));
            }
        }
        return found;
    }

    /** Rebuilds this chat's index from its canonical data. */
    public void rebuildIndex() throws IOException {
        index.rebuild(repository);
    }

    /** For tests: waits until no indexing task of any chat is queued or running. */
    static void awaitIndexing() throws InterruptedException {
        while (PENDING.get() > 0) Thread.sleep(10);
    }

    private void scheduleIndexing() {
        if (!indexQueued.compareAndSet(false, true)) return;
        PENDING.incrementAndGet();
        try {
            INDEXER.execute(() -> {
                indexQueued.set(false);
                try {
                    index.catchUp(repository);
                } catch (Exception failed) {
                    // The records are safe in the repository; the next search or record catches up.
                    System.err.println("[argos-history] background indexing failed: "
                            + failed.getClass().getSimpleName());
                } finally {
                    PENDING.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException full) {
            // Queue full: the index stays behind and the next search of this chat catches up.
            indexQueued.set(false);
            PENDING.decrementAndGet();
        }
    }

    /** About 240 characters around the first query word in the text, or its beginning. */
    static String snippet(String content, String query) {
        String text = content.replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String word : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() < 2) continue;
            int found = lower.indexOf(word);
            if (found >= 0 && (at < 0 || found < at)) at = found;
        }
        int from = Math.max(0, at < 0 ? 0 : at - 80);
        int to = Math.min(text.length(), from + 240);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static ThreadPoolExecutor indexer() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), runnable -> Thread.ofPlatform().daemon().name("argos-history-index")
                .unstarted(runnable));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
