package com.aresstack.argos.history;

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

/**
 * Hooks the Search History into the existing Argos paths without a second browser, search or research
 * implementation: a decorator around the shared {@link WebSearchPort} (one event per actual search call, also for
 * web_research), the {@link WebSession.PageListener} of the web_* tools, the browser_navigate/browser_snapshot
 * tools and a decorator around the research's {@link ResearchPageReader}.
 * <p>
 * Recording never changes a tool's result: every failure is logged (class name only) and swallowed. The single-file
 * HTML collector runs right after the page was read, while the browser still shows it.
 */
public final class SearchHistoryCapture {
    private static final LinkExtractor LINKS = new LinkExtractor();

    private final SearchHistory history;
    private final LivePages pages;
    private final boolean archiveHtml;
    private final int collectorTimeoutMillis;
    private volatile String contextId = "start";

    /** The browser operations the capture needs; implemented by the Camoufox adapter. */
    public interface LivePages {
        /** The page a tab shows (null = current tab), read like web_read: one serialized DOM, no page script. */
        PageCapture capture(String tabId) throws Exception;

        /** One transient evaluation of {@code function} in that tab (the single-file collector). */
        Object evaluate(String function, String tabId) throws Exception;
    }

    public SearchHistoryCapture(SearchHistory history, LivePages pages, boolean archiveHtml,
                                int collectorTimeoutMillis) {
        this.history = history;
        this.pages = pages;
        this.archiveHtml = archiveHtml;
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
                            page.text(), links), contextId, "web_research", true, archive(null, true));
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
                    capture.status(), capture.text(), links), contextId, tool, newVisit,
                    archive(tabId, !capture.html().isBlank()));
        } catch (Exception failed) {
            log(tool, failed);
        }
    }

    /** The collector for the shown page; null when there is no HTML document or archiving is switched off. */
    private SearchHistory.HtmlArchive archive(String tabId, boolean htmlDocument) {
        if (!archiveHtml || !htmlDocument) return null;
        return () -> HtmlCollector.collect(function -> pages.evaluate(function, tabId), collectorTimeoutMillis)
                .map(HtmlCollector.Collected::html);
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
