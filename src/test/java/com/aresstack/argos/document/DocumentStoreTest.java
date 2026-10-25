package com.aresstack.argos.document;

import com.aresstack.argos.fetch.FetchedResource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentStoreTest {
    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-25T12:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static FetchedResource resource(String url, String body) {
        return new FetchedResource(url, url, 200, "text/plain", "", body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storesMetadataWithoutCredentialsAndReadsBack() {
        try (DocumentStore store = new DocumentStore()) {
            StoredDocument document = store.store(new FetchedResource("https://user:pw@example.org/a/report.pdf?id=7",
                    "https://example.org/a/report.pdf?id=7", 200, "application/pdf", "", new byte[]{'%', 'P'}));
            assertEquals("doc-1", document.downloadId());
            assertEquals("https://example.org/a/report.pdf?id=7", document.sourceUrl());
            assertEquals("report.pdf", document.filename());
            assertEquals(2, document.size());
            assertFalse(document.metadata().containsKey("file"));
            assertFalse(document.metadata().toString().contains(document.file().toString()));
            assertEquals(2, store.read(document).length);
        }
    }

    @Test
    void expiresDocumentsAfterTimeToLive() {
        MovableClock clock = new MovableClock();
        try (DocumentStore store = new DocumentStore(100, 1_000, 10, Duration.ofMinutes(30), clock)) {
            StoredDocument document = store.store(resource("https://example.org/a.txt", "a"));
            clock.now = clock.now.plus(Duration.ofMinutes(31));
            assertTrue(store.find(document.downloadId()).isEmpty());
            assertFalse(Files.exists(document.file()));
        }
    }

    @Test
    void evictsOldestWhenCountOrSizeLimitIsReached() {
        try (DocumentStore store = new DocumentStore(10, 15, 2, Duration.ofMinutes(30), Clock.systemUTC())) {
            StoredDocument first = store.store(resource("https://example.org/1.txt", "1234567"));
            StoredDocument second = store.store(resource("https://example.org/2.txt", "1234567"));
            StoredDocument third = store.store(resource("https://example.org/3.txt", "1234567"));
            assertTrue(store.find(first.downloadId()).isEmpty());
            assertTrue(store.find(second.downloadId()).isPresent());
            assertTrue(store.find(third.downloadId()).isPresent());
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(resource("https://example.org/big.txt", "12345678901")));
        }
    }

    @Test
    void usesIdNotServerFilenameOnDiskAndSanitizesTheShownName() {
        try (DocumentStore store = new DocumentStore()) {
            StoredDocument document = store.store(new FetchedResource("https://example.org/x", "https://example.org/x",
                    200, "text/plain", "../../etc/pass:wd", new byte[]{'a'}));
            assertEquals(".._.._etc_pass_wd", document.filename());
            assertEquals("doc-1.txt", document.file().getFileName().toString());
        }
    }

    @Test
    void closeDeletesTheDirectory() {
        DocumentStore store = new DocumentStore();
        StoredDocument document = store.store(resource("https://example.org/a.txt", "a"));
        store.close();
        assertFalse(Files.exists(document.file().getParent()));
    }
}
