package com.aresstack.argos.history;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.aresstack.argos.history.HistoryFixtures.html;
import static com.aresstack.argos.history.HistoryFixtures.page;
import static org.junit.jupiter.api.Assertions.*;

/** Spec §10 items 1-4: persistence, Lucene and its rebuild, chat isolation, deduplication. */
class SearchHistoryTest {
    @TempDir Path chats;

    /** The background indexer must be done before the temporary directory is deleted. */
    @AfterEach void awaitIndexing() throws Exception {
        SearchHistory.awaitIndexing();
    }

    private Path historyOf(String chatKey) {
        return chats.resolve(chatKey).resolve("history");
    }

    @Test void eventsSurviveReopeningTheRepository() throws Exception {
        SearchHistory first = new SearchHistory(historyOf("a"));
        first.recordSearch("camoufox license", "duckduckgo.com", "start",
                List.of(new HistoryEvent.Result(1, "Camoufox", "https://camoufox.com/", "Anti-detect browser")));
        first.recordPage(page("https://camoufox.com/", "Camoufox", "Camoufox is MPL licensed."), "context-2",
                "web_open", true, null);

        SearchHistoryRepository reopened = new SearchHistoryRepository(historyOf("a"));
        assertEquals(2, reopened.lastSeq());
        List<HistoryEvent> events = new ArrayList<>();
        reopened.forEach(0, events::add);
        HistoryEvent.Search search = assertInstanceOf(HistoryEvent.Search.class, events.get(0));
        assertEquals("camoufox license", search.query());
        assertEquals("duckduckgo.com", search.provider());
        assertEquals("https://camoufox.com/", search.results().get(0).url());
        HistoryEvent.PageVisit visit = assertInstanceOf(HistoryEvent.PageVisit.class, events.get(1));
        assertEquals("context-2", visit.contextId());
        assertEquals("Camoufox is MPL licensed.", reopened.page(visit.snapshotId()).orElseThrow().content());
    }

    @Test void aTornLastLineIsSkippedAndNotGluedToTheNextEvent() throws Exception {
        SearchHistory history = new SearchHistory(historyOf("a"));
        history.recordSearch("first", null, null, List.of());
        Files.writeString(historyOf("a").resolve("events.jsonl"), "{\"seq\":2,\"type\":\"sea",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        SearchHistory reopened = new SearchHistory(historyOf("a"));
        reopened.recordSearch("second", null, null, List.of());
        List<String> queries = new ArrayList<>();
        reopened.repository().forEach(0, event -> queries.add(((HistoryEvent.Search) event).query()));
        assertEquals(List.of("first", "second"), queries);
    }

    @Test void storedPageTextIsFoundAndTheIndexIsRebuiltFromCanonicalData() throws Exception {
        SearchHistory history = new SearchHistory(historyOf("a"));
        history.recordPage(page("https://example.org/fox", "Foxes", "The quick brown fox jumps over the dog."),
                "start", "web_open", true, null);
        history.recordSearch("zebra migration", null, "start", List.of());

        List<SearchHistory.Found> found = history.search("brown fox", 10);
        assertEquals("https://example.org/fox", found.get(0).url());
        assertTrue(found.get(0).snippet().contains("quick brown fox"), found.get(0).snippet());
        assertEquals("search", history.search("zebra", 10).get(0).type());

        // The index is a projection: delete it, damage it, it comes back from events.jsonl and pages/.
        SearchHistory.awaitIndexing();
        Path lucene = historyOf("a").resolve("lucene");
        deleteTree(lucene);
        assertEquals("https://example.org/fox", history.search("fox", 10).get(0).url());
        try (Stream<Path> files = Files.list(lucene)) {
            for (Path file : files.toList()) Files.write(file, new byte[] {1, 2, 3});
        }
        SearchHistory reopened = new SearchHistory(historyOf("a"));
        assertEquals("https://example.org/fox", reopened.search("fox", 10).get(0).url());
        assertTrue(Files.isRegularFile(historyOf("a").resolve("events.jsonl")), "canonical data untouched");
    }

    @Test void aMissedBackgroundRunIsCaughtUpByTheNextSearch() throws Exception {
        SearchHistoryRepository repository = new SearchHistoryRepository(historyOf("a"));
        // Written straight to the canonical store, as if the background indexer had never run.
        repository.appendSearch(java.time.Instant.now(), "orphaned query", null, null, List.of());
        SearchHistory history = new SearchHistory(historyOf("a"));
        assertEquals(1, history.search("orphaned", 5).size());
    }

    @Test void chatsAreStrictlySeparatedButContextsOfOneChatShareTheHistory() throws Exception {
        SearchHistory chatA = new SearchHistory(historyOf("a"));
        SearchHistory chatB = new SearchHistory(historyOf("b"));
        chatA.recordPage(page("https://a.example/", "Alpha", "secret alpha research"), "start", "web_open", true, null);
        chatA.recordPage(page("https://a2.example/", "Alpha two", "alpha from another context"), "context-2",
                "web_open", true, null);
        chatB.recordPage(page("https://b.example/", "Beta", "beta research"), "start", "web_open", true, null);

        List<SearchHistory.Found> inA = chatA.search("alpha", 10);
        assertEquals(2, inA.size(), "both user contexts of chat A share one index");
        assertTrue(chatB.search("alpha", 10).isEmpty(), "chat B never sees chat A");
        assertTrue(chatA.search("beta", 10).isEmpty(), "chat A never sees chat B");
        assertTrue(Files.isDirectory(historyOf("a").resolve("lucene")));
        assertTrue(Files.isDirectory(historyOf("b").resolve("lucene")));
    }

    @Test void repeatedReadsReuseTheSnapshotAndChangedContentCreatesANewOne() throws Exception {
        SearchHistory history = new SearchHistory(historyOf("a"));
        int[] collected = {0};
        SearchHistory.HtmlArchive archive = () -> {
            collected[0]++;
            return java.util.Optional.of("<html><body>v1</body></html>");
        };
        SearchHistory.Recorded first = history.recordPage(page("https://x.example/", "X", "version one"), "start",
                "web_open", true, archive);
        assertTrue(first.newSnapshot());
        assertTrue(first.htmlArchived());
        assertNull(history.recordPage(page("https://x.example/", "X", "version one"), "start", "web_read", false,
                archive).visit(), "web_read part 2 of the same page is no new visit");
        SearchHistory.Recorded reopened = history.recordPage(page("https://x.example/", "X", "version one"), "start",
                "web_open", true, archive);
        assertNotNull(reopened.visit(), "opening again is a new visit");
        assertFalse(reopened.newSnapshot(), "... with the same snapshot");
        assertEquals(first.visit().snapshotId(), reopened.visit().snapshotId());
        assertEquals(1, collected[0], "the archive of an existing snapshot is not collected again");

        SearchHistory.Recorded changed = history.recordPage(page("https://x.example/", "X", "version two"), "start",
                "web_read", false, html("<html><body>v2</body></html>"));
        assertTrue(changed.newSnapshot(), "changed content is a new snapshot");
        assertNotEquals(first.visit().snapshotId(), changed.visit().snapshotId());
        try (Stream<Path> files = Files.list(historyOf("a").resolve("pages"))) {
            assertEquals(4, files.count(), "two snapshots: json + html each, no resource folders");
        }
        assertEquals(SearchHistoryRepository.contentHash("X", "version  one "),
                SearchHistoryRepository.contentHash("X", "version one"), "hash ignores whitespace, has no time");
    }

    @Test void aFailingArchiveKeepsTheTextRecordAndSaysWhy() throws Exception {
        SearchHistory history = new SearchHistory(historyOf("a"));
        SearchHistory.Recorded recorded = history.recordPage(page("https://x.example/", "X", "text"), "start",
                "web_open", true, () -> {
                    throw new IllegalStateException("page closed");
                });
        assertNotNull(recorded.visit());
        assertFalse(recorded.htmlArchived());
        StoredPage stored = history.repository().page(recorded.visit().snapshotId()).orElseThrow();
        assertEquals("text", stored.content());
        assertTrue(stored.htmlNote().contains("IllegalStateException"), stored.htmlNote());
    }

    @Test void secretUrlParametersAreRedacted() throws Exception {
        SearchHistory history = new SearchHistory(historyOf("a"));
        SearchHistory.Recorded recorded = history.recordPage(new SearchHistory.PageRead(
                "https://user:pw@x.example/cb?code=abc123&page=2#access_token=tok", null, "X", 200, "t",
                List.of("https://x.example/next?token=zzz")), "start", "web_open", true, null);
        String url = recorded.visit().url();
        assertFalse(url.contains("abc123") || url.contains("pw@") || url.contains("tok"), url);
        assertTrue(url.contains("page=2"), url);
        String links = history.repository().page(recorded.visit().snapshotId()).orElseThrow().links().toString();
        assertFalse(links.contains("zzz"), links);
    }

    static void deleteTree(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
