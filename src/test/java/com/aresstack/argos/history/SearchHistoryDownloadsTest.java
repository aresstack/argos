package com.aresstack.argos.history;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Export download links: bound to the creating chat's exports directory, no path input, expiring. */
class SearchHistoryDownloadsTest {
    @TempDir Path root;

    @AfterEach void awaitIndexing() throws Exception {
        SearchHistory.awaitIndexing();
    }

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-27T06:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private Path export(Path directory, String name) throws Exception {
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve(name), "data " + name);
    }

    @Test void registeredExportResolvesToItsFileOnly() throws Exception {
        SearchHistoryDownloads downloads = new SearchHistoryDownloads(Duration.ofMinutes(60), new MovableClock());
        downloads.origin("https://argos.example/");
        Path exports = root.resolve("a/history/exports");
        Path file = export(exports, "search-history-20261027-060000.jsonl");
        SearchHistoryDownloads.Link link = downloads.register(exports, file);
        assertEquals("https://argos.example/api/search-history/exports/" + link.id(), link.url());
        assertFalse(link.url().contains(root.toString()));
        SearchHistoryDownloads.Download download = downloads.resolve(link.id()).orElseThrow();
        assertEquals(file.toRealPath(), download.file());
        assertEquals("application/x-ndjson; charset=utf-8", download.contentType());
        for (String bad : List.of("", "../../etc/passwd", link.id() + "x", link.id().substring(1),
                "search-history-20261027-060000.jsonl")) {
            assertTrue(downloads.resolve(bad).isEmpty(), bad);
        }
    }

    @Test void filesOutsideTheCallingChatsExportsCannotBeRegistered() throws Exception {
        SearchHistoryDownloads downloads = new SearchHistoryDownloads(Duration.ofMinutes(60), new MovableClock());
        Path mine = root.resolve("a/history/exports");
        Path other = export(root.resolve("b/history/exports"), "search-history-20261027-060000.txt");
        Files.createDirectories(mine);
        assertThrows(IllegalArgumentException.class, () -> downloads.register(mine, other));
        assertThrows(IllegalArgumentException.class, () -> downloads.register(mine, export(mine, "secret.txt")));
    }

    @Test void swappedForASymlinkTheExportIsNotServed() throws Exception {
        SearchHistoryDownloads downloads = new SearchHistoryDownloads(Duration.ofMinutes(60), new MovableClock());
        Path exports = root.resolve("a/history/exports");
        Path file = export(exports, "search-history-20261027-060000.txt");
        SearchHistoryDownloads.Link link = downloads.register(exports, file);
        Files.delete(file);
        Path secret = Files.writeString(root.resolve("secret"), "secret");
        try {
            Files.createSymbolicLink(file, secret);
        } catch (UnsupportedOperationException | java.io.IOException noSymlinks) {
            return; // e.g. Windows without the privilege
        }
        assertTrue(downloads.resolve(link.id()).isEmpty());
    }

    @Test void expiredExportsAreForgottenAndDeleted() throws Exception {
        MovableClock clock = new MovableClock();
        SearchHistoryDownloads downloads = new SearchHistoryDownloads(Duration.ofMinutes(60), clock);
        Path exports = root.resolve("a/history/exports");
        Path leftover = export(exports, "search-history-20261026-010000.zip");
        Files.setLastModifiedTime(leftover, FileTime.from(clock.now.minus(Duration.ofHours(2))));
        Path file = export(exports, "search-history-20261027-060000.zip");
        SearchHistoryDownloads.Link link = downloads.register(exports, file);
        assertFalse(Files.exists(leftover), "leftover older than the retention is deleted on the next export");
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertTrue(downloads.resolve(link.id()).isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test void retentionIsConfigurable() {
        assertEquals(Duration.ofMinutes(60), SearchHistoryDownloads.retention(Map.of()));
        assertEquals(Duration.ofMinutes(5),
                SearchHistoryDownloads.retention(Map.of("ARGOS_SEARCH_HISTORY_EXPORT_TTL_MINUTES", "5")));
        assertThrows(IllegalArgumentException.class,
                () -> SearchHistoryDownloads.retention(Map.of("ARGOS_SEARCH_HISTORY_EXPORT_TTL_MINUTES", "x")));
    }

    @Test void everyFormatIsHandedOutAsLinkWithoutTheLocalPath() throws Exception {
        SearchHistoryDownloads downloads = new SearchHistoryDownloads(Duration.ofMinutes(60), new MovableClock());
        downloads.origin("https://argos.example");
        SearchHistory history = new SearchHistory(root.resolve("chat/history"));
        history.recordSearch("argos", "bing.com", "start", List.of());
        SyncToolSpecification export = new SearchHistoryTools(history, tab -> null, downloads).specifications().get(1);
        for (String format : List.of("txt", "jsonl", "zip")) {
            CallToolResult result = export.callHandler().apply(null,
                    new CallToolRequest("search_history_export", Map.of("format", format, "include_results", true)));
            assertNotEquals(Boolean.TRUE, result.isError(), format);
            String text = ((TextContent) result.content().get(0)).text();
            ResourceLink link = (ResourceLink) result.content().get(1);
            assertTrue(link.uri().startsWith("https://argos.example/api/search-history/exports/"), link.uri());
            assertTrue(link.name().endsWith("." + format));
            assertTrue(text.contains(link.uri()), text);
            assertFalse(text.contains(root.toString()), text);
            String id = link.uri().substring(link.uri().lastIndexOf('/') + 1);
            assertTrue(downloads.resolve(id).isPresent(), format);
        }
    }
}
