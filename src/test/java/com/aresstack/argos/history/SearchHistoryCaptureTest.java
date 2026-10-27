package com.aresstack.argos.history;

import com.aresstack.argos.research.ResearchPage;
import com.aresstack.argos.research.ResearchPageReader;
import com.aresstack.argos.web.PageCapture;
import com.aresstack.argos.web.PageText;
import com.aresstack.argos.web.SearchHit;
import com.aresstack.argos.web.SearchResults;
import com.aresstack.argos.web.UrlPolicy;
import com.aresstack.argos.web.WebSearchPort;
import com.aresstack.argos.web.WebSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec §10 item 5: normal searches and read pages are captured without the research feature. */
class SearchHistoryCaptureTest {
    @TempDir Path chat;

    /** The background indexer must be done before the temporary directory is deleted. */
    @AfterEach void awaitIndexing() throws Exception {
        SearchHistory.awaitIndexing();
    }
    private final HistoryFixtures.FakeBrowser browser = new HistoryFixtures.FakeBrowser();
    private final List<String> evaluated = new ArrayList<>();
    private SearchHistory history;
    private SearchHistoryCapture capture;

    @BeforeEach void setUp() {
        browser.pages.put("https://docs.example/", "<title>Docs</title><p>Argos documentation start.</p>"
                + "<a href='/api'>API</a>");
        browser.pages.put("https://docs.example/api", "<title>API</title><p>Search history API reference.</p>");
        history = new SearchHistory(chat.resolve("history"));
        capture = new SearchHistoryCapture(history, new SearchHistoryCapture.LivePages() {
            @Override
            public PageCapture capture(String tabId) {
                return browser.current();
            }

            @Override
            public Object evaluate(String function, String tabId) {
                evaluated.add(function);
                return "{\"title\":\"t\",\"url\":\"" + browser.currentUrl() + "\",\"html\":"
                        + "\"<!DOCTYPE html><html><body><script>x()</script><p>archived</p></body></html>\"}";
            }
        }, 5_000);
    }

    private List<HistoryEvent> events() throws Exception {
        List<HistoryEvent> events = new ArrayList<>();
        history.repository().forEach(0, events::add);
        return events;
    }

    @Test void webToolsRecordOneSearchEventPerCallAndOneVisitPerOpenedPage() throws Exception {
        WebSearchPort engine = query -> new SearchResults(List.of(
                new SearchHit("Docs", "https://docs.example/", "Argos docs"),
                new SearchHit("Never opened", "https://other.example/", "")), List.of("PROVIDER: bing.com"));
        WebSession web = new WebSession(browser, UrlPolicy.off(), capture.capturingSearch(engine));
        web.onPageRead(capture.webPages());
        capture.useContext("context-2");

        web.search("argos docs");
        web.search("argos docs");
        web.openResult("result-3");                // the second search continues the ids
        web.read(1);
        web.find("documentation");
        web.follow("link-" + web.links().split(":")[0].substring("link-".length()));
        web.back();

        List<HistoryEvent> events = events();
        List<String> kinds = events.stream().map(e -> e instanceof HistoryEvent.Search ? "search"
                : ((HistoryEvent.PageVisit) e).source()).toList();
        assertEquals(List.of("search", "search", "web_open", "web_open", "web_back"), kinds,
                "identical searches stay separate; re-reads of the shown page add no visit");
        HistoryEvent.Search search = (HistoryEvent.Search) events.get(0);
        assertEquals("bing.com", search.provider());
        assertEquals(2, search.results().size());
        assertEquals("context-2", search.contextId());
        HistoryEvent.PageVisit back = (HistoryEvent.PageVisit) events.get(4);
        HistoryEvent.PageVisit first = (HistoryEvent.PageVisit) events.get(2);
        assertEquals(first.snapshotId(), back.snapshotId(), "going back reuses the snapshot");
        assertTrue(evaluated.isEmpty(), "web_* tools never run the HTML collector");
        assertFalse(history.repository().hasHtml(first.snapshotId()));
        assertTrue(history.search("reference", 5).get(0).url().endsWith("/api"));
    }

    @Test void aFailedSearchIsNoEventAndAFailingRecordNeverFailsTheTool() throws Exception {
        WebSearchPort failing = query -> {
            throw new IllegalStateException("no engine");
        };
        WebSession web = new WebSession(browser, UrlPolicy.off(), capture.capturingSearch(failing));
        assertThrows(IllegalStateException.class, () -> web.search("x"));
        assertTrue(events().isEmpty());

        WebSession broken = new WebSession(browser, UrlPolicy.off(), null);
        broken.onPageRead((page, requested, newVisit, tool) -> {
            throw new IllegalStateException("disk full");
        });
        assertTrue(broken.open("https://docs.example/").contains("Argos documentation start."));
    }

    @Test void browserToolsAndTheResearchReaderAreRecorded() throws Exception {
        browser.open("https://docs.example/", null);
        capture.browserPage(null, "https://docs.example/", true, "browser_navigate");
        capture.browserPage(null, null, false, "browser_snapshot");
        ResearchPageReader reader = capture.capturingReader(url -> new ResearchPage(url, "API", 200,
                "Search history API reference.", List.of(new ResearchPage.PageLink("Home", "https://docs.example/")),
                ResearchPage.Access.READABLE));
        reader.read("https://docs.example/api");

        List<String> sources = events().stream().map(e -> ((HistoryEvent.PageVisit) e).source()).toList();
        assertEquals(List.of("browser_navigate", "web_research"), sources);
    }

    @Test void feedsAndOtherNonHtmlDocumentsAreRecordedWithoutRunningTheCollector() throws Exception {
        capture.webPages().pageRead(new PageCapture("https://feed.example/rss", "", "1. Entry", "", 200),
                "https://feed.example/rss", true, "web_open");
        HistoryEvent.PageVisit visit = (HistoryEvent.PageVisit) events().get(0);
        assertTrue(evaluated.isEmpty());
        assertNotNull(history.repository().page(visit.snapshotId()).orElseThrow().htmlNote());
        // Non-web pages (about:blank, file:) are not part of the history.
        capture.webPages().pageRead(PageText.capture("about:blank", "", 0), null, true, "web_open");
        assertEquals(1, events().size());
    }
}
