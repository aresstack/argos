package com.aresstack.argos.history;

import com.aresstack.argos.network.ResponseContentSink;
import com.aresstack.argos.research.ResearchPage;
import com.aresstack.argos.research.ResearchPageReader;
import com.aresstack.argos.web.LinkExtractor;
import com.aresstack.argos.web.PageCapture;
import com.aresstack.argos.web.SearchHit;
import com.aresstack.argos.web.SearchResults;
import com.aresstack.argos.web.WebSearchPort;
import com.aresstack.argos.web.WebSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hooks the Search History into the existing Argos paths without a second browser, search or research
 * implementation: a decorator around the shared {@link WebSearchPort} (one event per actual search call, also for
 * web_research), the {@link WebSession.PageListener} of the web_* tools, the browser_navigate/browser_snapshot
 * tools, a decorator around the research's {@link ResearchPageReader} and a {@link ResponseContentSink} on the
 * browser's existing network recorder for received response bodies ({@link #networkContent}).
 * <p>
 * Recording never changes a tool's result: every failure is logged (class name only) and swallowed. None of these
 * paths runs a page script or causes a request: the single-file HTML collector runs ONLY in {@link #archiveHtml},
 * the explicit browser_archive_html call.
 */
public final class SearchHistoryCapture {
    private static final LinkExtractor LINKS = new LinkExtractor();

    private final SearchHistory history;
    private final LivePages pages;
    private final int collectorTimeoutMillis;
    private volatile String contextId = "start";

    /** The browser operations the capture needs; implemented by the Camoufox adapter. */
    public interface LivePages {
        /** The page a tab shows (null = current tab), read like web_read: one serialized DOM, no page script. */
        PageCapture capture(String tabId) throws Exception;

        /** One transient evaluation of {@code function} in that tab (only browser_archive_html uses it). */
        Object evaluate(String function, String tabId) throws Exception;
    }

    public SearchHistoryCapture(SearchHistory history, LivePages pages, int collectorTimeoutMillis) {
        this.history = history;
        this.pages = pages;
        this.collectorTimeoutMillis = collectorTimeoutMillis;
    }

    public SearchHistory history() {
        return history;
    }

    /** The user context of the following calls; stored as metadata, never used to separate histories. */
    public void useContext(String id) {
        contextId = id == null || id.isBlank() ? "start" : id;
    }

    /** The search port with one search event per successful call. */
    public WebSearchPort capturingSearch(WebSearchPort search) {
        return query -> {
            SearchResults results = search.search(query);
            try {
                List<HistoryEvent.Result> hits = new ArrayList<>();
                int rank = 1;
                for (SearchHit hit : results.hits()) {
                    hits.add(new HistoryEvent.Result(rank++, hit.title(), hit.url(), hit.snippet()));
                }
                history.recordSearch(query, provider(results.notes()), contextId, hits);
            } catch (Exception failed) {
                log("search", failed);
            }
            return results;
        };
    }

    /**
     * The convenience profile's web_search: one event for the whole call, all engines together ({@code providers}
     * names each engine with its status), with the merged result list in its answer order.
     */
    public void search(String query, String providers, List<SearchHit> hits) {
        try {
            List<HistoryEvent.Result> results = new ArrayList<>();
            int rank = 1;
            for (SearchHit hit : hits) results.add(new HistoryEvent.Result(rank++, hit.title(), hit.url(), hit.snippet()));
            history.recordSearch(query, providers, contextId, results);
        } catch (Exception failed) {
            log("web_search", failed);
        }
    }

    /** A page a convenience tool loaded or read in {@code tabId}; the capture is the one the tool answered with. */
    public void tabPage(PageCapture capture, String requestedUrl, boolean newVisit, String tool, String tabId) {
        record(capture, requestedUrl, newVisit, tool, tabId);
    }

    /** The provider host(s) the search path reports as {@code PROVIDER: host} notes; null when unknown. */
    static String provider(List<String> notes) {
        List<String> hosts = new ArrayList<>();
        for (String note : notes) {
            if (note.startsWith("PROVIDER: ")) hosts.add(note.substring("PROVIDER: ".length()).strip());
        }
        return hosts.isEmpty() ? null : String.join(", ", hosts);
    }

    /** Listener for {@link WebSession}: called after a page passed the URL policy, while it is shown. */
    public WebSession.PageListener webPages() {
        return (capture, requestedUrl, newVisit, tool) -> record(capture, requestedUrl, newVisit, tool, null);
    }

    /**
     * browser_navigate (newVisit) and browser_snapshot (re-read): reads the tab once more (serialized DOM, as
     * web_read) and records it.
     */
    public void browserPage(String tabId, String requestedUrl, boolean newVisit, String tool) {
        try {
            record(pages.capture(tabId), requestedUrl, newVisit, tool, tabId);
        } catch (Exception failed) {
            log(tool, failed);
        }
    }

    /** The research reader with each read page recorded (and archived while it is still shown). */
    public ResearchPageReader capturingReader(ResearchPageReader reader) {
        return url -> {
            ResearchPage page = reader.read(url);
            try {
                if (isWeb(page.url())) {
                    List<String> links = page.links().stream().map(ResearchPage.PageLink::url).toList();
                    history.recordPage(new SearchHistory.PageRead(page.url(), url, page.title(), page.status(),
                            page.text(), links), contextId, "web_research", true);
                }
            } catch (Exception failed) {
                log("web_research", failed);
            }
            return page;
        };
    }

    private void record(PageCapture capture, String requestedUrl, boolean newVisit, String tool, String tabId) {
        try {
            if (capture == null || !isWeb(capture.url())) return;
            List<String> links = new ArrayList<>();
            for (LinkExtractor.Anchor anchor : LINKS.extract(capture.html(), capture.url())) links.add(anchor.url());
            history.recordPage(new SearchHistory.PageRead(capture.url(), requestedUrl, capture.title(),
                    capture.status(), capture.text(), links), contextId, tool, newVisit);
        } catch (Exception failed) {
            log(tool, failed);
        }
    }

    // ------------------------------------------------------------------ explicit single-file archive

    /** The result of browser_archive_html: the sanitized single-file HTML and the page it shows. */
    public record HtmlArchiveResult(String url, String title, String html, String snapshotId) { }

    /**
     * browser_archive_html: archives the page the tab shows NOW (no navigation). Records the page's text first (so a
     * collector failure or timeout never costs the text history), then runs the existing cam-tool collector once in
     * the page; that collector may request the page's images and stylesheets again.
     */
    public HtmlArchiveResult archiveHtml(String tabId) throws Exception {
        PageCapture capture = pages.capture(tabId);
        if (capture == null || !isWeb(capture.url())) {
            throw new IllegalStateException("The tab shows no http(s) page; open the page first with the browser tools.");
        }
        if (capture.html().isBlank()) {
            throw new IllegalStateException("The tab shows no HTML document (feed, JSON, text or binary document).");
        }
        List<String> links = new ArrayList<>();
        for (LinkExtractor.Anchor anchor : LINKS.extract(capture.html(), capture.url())) links.add(anchor.url());
        SearchHistory.PageRead read = new SearchHistory.PageRead(capture.url(), null, capture.title(),
                capture.status(), capture.text(), links);
        history.ensureSnapshot(read, contextId);
        Optional<HtmlCollector.Collected> collected = HtmlCollector.collect(
                function -> pages.evaluate(function, tabId), collectorTimeoutMillis);
        if (collected.isEmpty()) {
            throw new IllegalStateException("The single-file collector returned no HTML (timeout after "
                    + collectorTimeoutMillis + " ms or page error). The page text is in the history.");
        }
        SearchHistory.Archived archived = history.archiveHtml(read, contextId, collected.get().html());
        return new HtmlArchiveResult(ArchiveSanitizer.safeUrl(capture.url()), capture.title(), archived.html(),
                archived.snapshotId());
    }

    // ------------------------------------------------------------------ network content

    /** Largest body read for text categories (content, scripts, styles); larger ones are skipped. */
    public static final long MAX_TEXT_BODY_BYTES = 8L * 1024 * 1024;
    /** Largest body read for binary categories. Every category needs an announced, unencoded Content-Length. */
    public static final long MAX_BINARY_BODY_BYTES = 32L * 1024 * 1024;
    /** Bodies waiting for the writer, of all chats; beyond it new bodies are dropped (counted). */
    static final long MAX_QUEUED_BYTES = 64L * 1024 * 1024;
    private static final ThreadPoolExecutor WRITER = writer();
    private static final AtomicLong QUEUED_BYTES = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();

    /**
     * The sink for the browser's network recorder: decides per finished response from its MIME category and this
     * chat's {@link CaptureSettings}, then stores the body the browser already received. Persistence runs on a
     * bounded background writer, never on the browser driver thread.
     */
    public ResponseContentSink networkContent() {
        return new ResponseContentSink() {
            @Override
            public long wants(ObservedResponse response) {
                return limit(response, history.captureSettings());
            }

            @Override
            public void deliver(ObservedResponse response, byte[] body, String unavailable) {
                if (body == null) return; // missing, evicted or too large: nothing is stored, nothing fails
                String context = contextId;
                if (QUEUED_BYTES.addAndGet(body.length) > MAX_QUEUED_BYTES) {
                    QUEUED_BYTES.addAndGet(-body.length);
                    DROPPED.incrementAndGet();
                    return;
                }
                try {
                    WRITER.execute(() -> {
                        try {
                            history.recordResource(new SearchHistory.Received(response.url(), response.pageUrl(),
                                    response.status(), response.contentType(), response.resourceType(), body,
                                    response.contentLength(), response.contentRange(), response.encoded()), context);
                        } catch (Exception failed) {
                            log("network capture", failed);
                        } finally {
                            QUEUED_BYTES.addAndGet(-body.length);
                        }
                    });
                } catch (RejectedExecutionException full) {
                    QUEUED_BYTES.addAndGet(-body.length);
                    DROPPED.incrementAndGet();
                }
            }
        };
    }

    /**
     * The largest body worth reading for this response, 0 = not wanted. Response.body() loads the whole body into
     * the heap without streaming, so only responses with an announced, unencoded Content-Length within the limit are
     * read (text and binary alike); chunked or compressed responses are skipped.
     */
    static long limit(ResponseContentSink.ObservedResponse response, CaptureSettings settings) {
        String url = response.url() == null ? "" : response.url().toLowerCase(Locale.ROOT);
        if (!url.startsWith("http://") && !url.startsWith("https://")) return 0;
        ResourceCategory category = ResourceCategory.classify(response.contentType());
        if (!settings.enabled(category)) return 0;
        long max = ResourceCategory.textKind(response.contentType()) != ResourceCategory.TextKind.BINARY
                ? MAX_TEXT_BODY_BYTES : MAX_BINARY_BODY_BYTES;
        Long length = response.contentLength();
        if (length == null || response.encoded() || length <= 0 || length > max) return 0;
        return max;
    }

    /** For tests: waits until every queued body was written. */
    public static void awaitWriter() throws InterruptedException {
        while (WRITER.getActiveCount() > 0 || !WRITER.getQueue().isEmpty()) Thread.sleep(10);
    }

    private static ThreadPoolExecutor writer() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), runnable -> Thread.ofPlatform().daemon().name("argos-history-writer")
                .unstarted(runnable));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static boolean isWeb(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Diagnostics without URLs, page data or session ids. */
    private static void log(String tool, Exception failed) {
        System.err.println("[argos-history] recording after " + tool + " failed: "
                + failed.getClass().getSimpleName());
    }
}
