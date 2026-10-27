package com.aresstack.argos.history;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    private final SearchHistoryRepository repository;
    private final LuceneSearchHistoryIndex index;
    private final AtomicBoolean indexQueued = new AtomicBoolean();
    /** The page recorded last, so re-reading the same page (web_read part 2) records no new visit. */
    private String lastUrl;
    private String lastSnapshot;

    public SearchHistory(Path historyDirectory) {
        this.repository = new SearchHistoryRepository(historyDirectory);
        this.index = new LuceneSearchHistoryIndex(repository.luceneDirectory());
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

    /** Produces the single-file HTML of the page while it is still shown; empty when that failed. */
    @FunctionalInterface
    public interface HtmlArchive {
        Optional<String> collect() throws Exception;
    }

    /** What a page record did. {@code visit} is null when a re-read of the same content recorded nothing. */
    public record Recorded(HistoryEvent.PageVisit visit, boolean newSnapshot, boolean htmlArchived) { }

    /** One search result of {@link #search}. {@code type} is "page" or "search". */
    public record Found(String type, Instant at, String title, String url, String snippet, String contextId) { }

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
     * content creates a new one. {@code html} runs only when that snapshot has no archive yet.
     */
    public synchronized Recorded recordPage(PageRead page, String contextId, String source, boolean newVisit,
                                            HtmlArchive html) throws IOException {
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
            stored = new StoredPage(snapshotId, url, page.title(), page.status(), hash, at, page.text(), links, null);
            repository.savePage(stored);
        }
        // The text record exists before the archive is tried: a failed archive never costs the history.
        boolean archived = repository.hasHtml(snapshotId);
        if (!archived && html != null && (newSnapshot || newVisit)) {
            String note;
            try {
                Optional<String> collected = html.collect();
                if (collected.isPresent()) {
                    repository.saveHtml(snapshotId, ArchiveSanitizer.sanitize(collected.get(), page.url()));
                    archived = true;
                    note = null;
                } else {
                    note = "The single-file collector returned no HTML (timeout or page error).";
                }
            } catch (Exception failed) {
                note = "The single-file collector failed: " + failed.getClass().getSimpleName();
            }
            if (!archived || stored.htmlNote() != null) {
                stored = new StoredPage(stored.snapshotId(), stored.url(), stored.title(), stored.status(),
                        stored.contentHash(), stored.capturedAt(), stored.content(), stored.links(), note);
                repository.savePage(stored);
            }
        } else if (!archived && html == null && stored.htmlNote() == null) {
            stored = new StoredPage(stored.snapshotId(), stored.url(), stored.title(), stored.status(),
                    stored.contentHash(), stored.capturedAt(), stored.content(), stored.links(),
                    "No HTML document to archive (feed, JSON, text or binary document, or archiving is off).");
            repository.savePage(stored);
        }
        HistoryEvent.PageVisit visit = repository.appendVisit(at, url, requested, page.title(), page.status(),
                contextId, snapshotId, hash, source);
        lastUrl = url;
        lastSnapshot = snapshotId;
        scheduleIndexing();
        return new Recorded(visit, newSnapshot, archived);
    }

    /**
     * Full-text search over this chat's history. Brings the chat's index up to date first (also after a failed
     * background run, or rebuilt when it is missing or damaged), so a search always sees every stored record.
     */
    public List<Found> search(String query, int limit) throws IOException {
        index.catchUp(repository);
        List<Found> found = new ArrayList<>();
        for (LuceneSearchHistoryIndex.Hit hit : index.search(query, Math.max(1, Math.min(50, limit)))) {
            if ("page".equals(hit.type())) {
                String content = repository.page(hit.snapshotId()).map(StoredPage::content).orElse("");
                found.add(new Found("page", hit.at(), hit.title(), hit.url(), snippet(content, query),
                        hit.contextId()));
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
