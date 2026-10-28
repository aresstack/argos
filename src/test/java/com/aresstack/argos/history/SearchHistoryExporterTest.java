package com.aresstack.argos.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.aresstack.argos.history.HistoryFixtures.page;
import static org.junit.jupiter.api.Assertions.*;

/** Spec §10 item 7: TXT, JSONL and ZIP exports (ZIP checks modeled on cam-tool's test_favorites_store). */
class SearchHistoryExporterTest {
    @TempDir Path chat;

    /** The background indexer must be done before the temporary directory is deleted. */
    @AfterEach void awaitIndexing() throws Exception {
        SearchHistory.awaitIndexing();
    }
    private SearchHistory history;
    private SearchHistoryExporter exporter;

    @BeforeEach void record() throws Exception {
        history = new SearchHistory(chat.resolve("history"));
        exporter = new SearchHistoryExporter(history.repository());
        history.recordSearch("argos", "bing.com", "start", List.of(
                new HistoryEvent.Result(1, "Example", "https://example.com/", "snippet one"),
                new HistoryEvent.Result(2, "Not opened", "https://unopened.example/", null)));
        history.recordPage(page("https://example.com/", "Example: Home/../etc", "Home text",
                "https://example.com/docs"), "start", "web_open", true);
        // Explicit browser_archive_html results; no automatic archive exists.
        history.archiveHtml(page("https://example.com/", "Example: Home/../etc", "Home text",
                "https://example.com/docs"), "start", "<html><body>home</body></html>");
        history.recordPage(page("https://example.com/docs", "Example: Home/../etc", "Docs text"), "context-2",
                "web_open", true);
        history.recordPage(page("https://example.com/", "Example: Home/../etc", "Home text",
                "https://example.com/docs"), "start", "web_back", true);
        history.recordPage(page("https://openai.com/", "", "OpenAI"), "start", "web_open", true);
        history.archiveHtml(page("https://openai.com/", "", "OpenAI"), "start", "<html><body>openai</body></html>");
    }

    @Test void txtListsVisitedUrlsInOrderOptionallyUnique() throws Exception {
        SearchHistoryExporter.Export all = exporter.export(SearchHistoryExporter.Format.TXT, false, false);
        assertEquals(List.of("https://example.com/", "https://example.com/docs", "https://example.com/",
                "https://openai.com/"), Files.readAllLines(all.file(), StandardCharsets.UTF_8));
        assertTrue(all.file().startsWith(chat.resolve("history").resolve("exports")));
        assertTrue(all.file().getFileName().toString().endsWith(".txt"));

        SearchHistoryExporter.Export unique = exporter.export(SearchHistoryExporter.Format.TXT, true, false);
        assertNotEquals(all.file(), unique.file(), "exports never overwrite each other");
        assertEquals(List.of("https://example.com/", "https://example.com/docs", "https://openai.com/"),
                Files.readAllLines(unique.file()));

        SearchHistoryExporter.Export withResults = exporter.export(SearchHistoryExporter.Format.TXT, true, true);
        assertEquals(List.of("https://example.com/", "https://unopened.example/", "https://example.com/docs",
                "https://openai.com/"), Files.readAllLines(withResults.file()));
    }

    @Test void jsonlHasOneValidRecordPerLineWithContentAndMetadata() throws Exception {
        SearchHistoryExporter.Export export = exporter.export(SearchHistoryExporter.Format.JSONL, false, false);
        List<String> lines = Files.readAllLines(export.file(), StandardCharsets.UTF_8);
        assertEquals(5, lines.size());
        ObjectMapper json = new ObjectMapper();
        JsonNode search = json.readTree(lines.get(0));
        assertEquals("search", search.get("type").asText());
        assertEquals("argos", search.get("query").asText());
        assertEquals("bing.com", search.get("provider").asText());
        assertEquals(2, search.get("results").get(1).get("rank").asInt());
        assertTrue(search.get("results").get(1).get("snippet").isNull(), "missing values are null");
        JsonNode visit = json.readTree(lines.get(1));
        assertEquals("page_visit", visit.get("type").asText());
        assertEquals("Home text", visit.get("content").asText());
        assertEquals("https://example.com/docs", visit.get("links").get(0).asText());
        assertEquals(200, visit.get("status").asInt());
        assertEquals("start", visit.get("contextId").asText());
        assertFalse(visit.get("contentHash").asText().isEmpty());
        assertTrue(visit.get("visitedAt").asText().endsWith("Z"));
        assertEquals("context-2", json.readTree(lines.get(2)).get("contextId").asText());
        assertFalse(String.join("\n", lines).contains("<html>"), "the HTML archive is not part of the JSONL");
    }

    @Test void zipHoldsAnIndexAndOneSelfContainedHtmlFilePerSnapshot() throws Exception {
        SearchHistoryExporter.Export export = exporter.export(SearchHistoryExporter.Format.ZIP, false, false);
        assertEquals(3, export.records());
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(export.file()); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                assertFalse(entry.isDirectory(), "no resource folders: " + entry.getName());
                assertFalse(entry.getName().contains("/") || entry.getName().contains("\\"), entry.getName());
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(Set.of("index.html", "Example- Home-etc.html", "Example- Home-etc-1.html",
                "openai.com.html"), entries.keySet(), "names from titles, sanitized, collisions numbered");
        assertTrue(entries.get("Example- Home-etc.html").contains("<body>home</body>"), "the stored archive as is");
        String fallback = entries.get("Example- Home-etc-1.html");
        assertTrue(fallback.contains("Textbasierter Fallback") && fallback.contains("Docs text"), fallback);
        String index = entries.get("index.html");
        for (String name : entries.keySet()) {
            if (!name.equals("index.html")) assertTrue(index.contains("href=\"" + name + "\""), name);
        }
        assertTrue(index.contains("https://openai.com/") && index.contains("200"));
        assertTrue(index.contains("<td>2</td>"), "the home page was visited twice");
        assertFalse(index.contains("<script") || index.contains("<link"), "the index needs nothing external");
    }

    @Test void fileNamesNeverBecomePaths() {
        Set<String> used = new HashSet<>(Set.of("index.html"));
        assertEquals("_index.html", SearchHistoryExporter.uniqueName(used, SearchHistoryExporter.fileStem("index", "")));
        assertEquals("_CON.html", SearchHistoryExporter.uniqueName(used, SearchHistoryExporter.fileStem("CON", "")));
        assertEquals("etc-passwd.html", SearchHistoryExporter.uniqueName(used,
                SearchHistoryExporter.fileStem("../../etc/passwd", "")));
        assertEquals("Seite.html", SearchHistoryExporter.uniqueName(used, SearchHistoryExporter.fileStem("", "x")));
        assertThrows(IllegalArgumentException.class, () -> SearchHistoryExporter.format("../x"));
    }
}
