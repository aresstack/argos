package com.aresstack.argos.document;

import com.aresstack.argos.fetch.FetchedResource;
import com.aresstack.argos.fetch.WebAddress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Temporary, process-local storage for downloaded documents. Each document lives in its own file named by its
 * id (never by the server's file name), is deleted after {@code timeToLive}, and the oldest documents are evicted
 * when the count or total size limit is reached. The directory is removed on {@link #close()}.
 */
public final class DocumentStore implements AutoCloseable {
    public static final long DEFAULT_MAX_DOCUMENT_BYTES = 20L * 1024 * 1024;
    public static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofMinutes(30);

    private final Map<String, StoredDocument> documents = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final long maxDocumentBytes;
    private final long maxTotalBytes;
    private final int maxDocuments;
    private final Duration timeToLive;
    private final Clock clock;
    private Path directory;

    public DocumentStore() {
        this(DEFAULT_MAX_DOCUMENT_BYTES, 200L * 1024 * 1024, 50, DEFAULT_TIME_TO_LIVE, Clock.systemUTC());
    }

    public DocumentStore(long maxDocumentBytes, long maxTotalBytes, int maxDocuments, Duration timeToLive,
                         Clock clock) {
        this.maxDocumentBytes = maxDocumentBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxDocuments = maxDocuments;
        this.timeToLive = timeToLive;
        this.clock = clock;
    }

    public long maxDocumentBytes() {
        return maxDocumentBytes;
    }

    public synchronized StoredDocument store(FetchedResource resource) {
        if (resource.size() > maxDocumentBytes) {
            throw new IllegalArgumentException("Document exceeds " + maxDocumentBytes + " bytes");
        }
        purgeExpired();
        while (!documents.isEmpty() && (documents.size() >= maxDocuments
                || totalBytes() + resource.size() > maxTotalBytes)) {
            remove(documents.keySet().iterator().next());
        }
        String filename = filenameOf(resource);
        DocumentFormat format = DocumentFormat.detect(resource.contentType(), filename, resource.content());
        String id = "doc-" + sequence.incrementAndGet();
        Path file = directory().resolve(id + format.extension());
        try {
            Files.write(file, resource.content());
        } catch (IOException error) {
            throw new UncheckedIOException("Could not store document " + id, error);
        }
        Instant now = clock.instant();
        String contentType = resource.contentType().isBlank() ? format.mediaType() : resource.contentType();
        StoredDocument document = new StoredDocument(id, WebAddress.withoutUserInfo(resource.requestedUrl()),
                WebAddress.withoutUserInfo(resource.finalUrl()), filename, contentType, format, resource.size(),
                now, now.plus(timeToLive), file);
        documents.put(id, document);
        return document;
    }

    public synchronized Optional<StoredDocument> find(String downloadId) {
        purgeExpired();
        return Optional.ofNullable(documents.get(downloadId == null ? "" : downloadId.trim()));
    }

    public synchronized byte[] read(StoredDocument document) {
        try {
            return Files.readAllBytes(document.file());
        } catch (IOException error) {
            throw new UncheckedIOException("Document " + document.downloadId() + " is no longer available", error);
        }
    }

    public synchronized List<StoredDocument> list() {
        purgeExpired();
        return List.copyOf(documents.values());
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        documents.values().stream().filter(document -> !document.expiresAt().isAfter(now))
                .map(StoredDocument::downloadId).toList().forEach(this::remove);
    }

    private void remove(String id) {
        StoredDocument removed = documents.remove(id);
        if (removed == null) return;
        try {
            Files.deleteIfExists(removed.file());
        } catch (IOException ignored) {
            // A file that cannot be deleted now is removed with the directory on close.
        }
    }

    private long totalBytes() {
        return documents.values().stream().mapToLong(StoredDocument::size).sum();
    }

    private Path directory() {
        if (directory != null) return directory;
        try {
            directory = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? Files.createTempDirectory("argos-documents-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                    : Files.createTempDirectory("argos-documents-");
            return directory;
        } catch (IOException error) {
            throw new UncheckedIOException("Could not create the temporary document directory", error);
        }
    }

    static String filenameOf(FetchedResource resource) {
        String name = resource.suggestedFilename();
        if (name.isBlank()) {
            try {
                String path = URI.create(resource.finalUrl()).getPath();
                name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            } catch (IllegalArgumentException invalid) {
                name = "";
            }
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "document";
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    @Override
    public synchronized void close() {
        List.copyOf(documents.keySet()).forEach(this::remove);
        if (directory == null) return;
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: the OS temp cleaner removes leftovers.
                }
            });
        } catch (IOException ignored) {
            // Directory already gone.
        }
        directory = null;
    }
}
