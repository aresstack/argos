package com.aresstack.argos.history;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Download links for Search History exports, served by {@link SearchHistoryDownloadServlet} on the existing Argos
 * HTTP endpoint at {@code /api/search-history/exports/{exportId}}.
 * <p>
 * An export id is 256 random bits and is the only credential of the link (a browser download carries no bearer
 * token). It maps to the exports directory of the chat whose tool call created it plus a file name Argos chose; the
 * id never contains a path, and resolution only ever opens a regular file directly inside that one directory, so an
 * id can neither reach another chat's exports nor any other file. Ids live in memory only and expire after the
 * retention ({@code ARGOS_SEARCH_HISTORY_EXPORT_TTL_MINUTES}, default 60, 1-1440); expired exports are deleted, and
 * leftover exports of a chat older than the retention (for example from before a restart) are deleted on its next
 * export.
 */
public final class SearchHistoryDownloads {
    public static final String PATH = "/api/search-history/exports/";
    static final Duration DEFAULT_RETENTION = Duration.ofMinutes(60);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern FILE = Pattern.compile("search-history-[0-9TZ-]+(-\\d+)?\\.(txt|jsonl|zip)");
    private static volatile SearchHistoryDownloads shared;

    private final Duration retention;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile String origin;

    private record Entry(Path directory, String fileName, Instant expires) { }

    /** A resolved, still valid export. */
    public record Download(Path file, String fileName, String contentType, long size) { }

    /** A registered export: its id, link (null while no origin is known) and expiry. */
    public record Link(String id, String url, String fileName, String contentType, long size, Instant expires) { }

    public SearchHistoryDownloads(Duration retention, Clock clock) {
        this.retention = retention;
        this.clock = clock;
    }

    /** The process-wide registry: the tools register into it, the servlet of the running server reads from it. */
    public static SearchHistoryDownloads shared() {
        SearchHistoryDownloads current = shared;
        if (current == null) {
            synchronized (SearchHistoryDownloads.class) {
                if (shared == null) shared = new SearchHistoryDownloads(retention(System.getenv()), Clock.systemUTC());
                current = shared;
            }
        }
        return current;
    }

    static Duration retention(Map<String, String> environment) {
        String configured = environment.getOrDefault("ARGOS_SEARCH_HISTORY_EXPORT_TTL_MINUTES", "").strip();
        if (configured.isEmpty()) return DEFAULT_RETENTION;
        if (!configured.matches("\\d{1,4}")) {
            throw new IllegalArgumentException("ARGOS_SEARCH_HISTORY_EXPORT_TTL_MINUTES must be minutes: " + configured);
        }
        return Duration.ofMinutes(Math.max(1, Math.min(1440, Integer.parseInt(configured))));
    }

    public Duration retention() {
        return retention;
    }

    /**
     * The origin the calling client reaches this server under: PUBLIC_ORIGIN when set (the HTTPS address ChatGPT
     * already uses for MCP), otherwise the local listen address. Set once by the server start.
     */
    public void origin(String origin) {
        this.origin = origin == null ? null : origin.replaceAll("/+$", "");
    }

    /** Registers a finished export of the chat whose exports directory is {@code directory}. */
    public Link register(Path directory, Path file) throws IOException {
        purge();
        String fileName = file.getFileName().toString();
        if (!FILE.matcher(fileName).matches() || !directory.equals(file.getParent())) {
            throw new IllegalArgumentException("Not an export of this chat.");
        }
        deleteStale(directory, file);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = clock.instant().plus(retention);
        entries.put(id, new Entry(directory, fileName, expires));
        String base = origin;
        return new Link(id, base == null ? null : base + PATH + id, fileName, contentType(fileName),
                Files.size(file), expires);
    }

    /** The export behind {@code id}, or empty when the id is malformed, unknown, expired or its file is gone. */
    public Optional<Download> resolve(String id) {
        if (id == null || !ID.matcher(id).matches()) return Optional.empty();
        Entry entry = entries.get(id);
        if (entry == null) return Optional.empty();
        if (!clock.instant().isBefore(entry.expires())) {
            expire(id, entry);
            return Optional.empty();
        }
        try {
            Path directory = entry.directory().toRealPath();
            Path file = directory.resolve(entry.fileName()).normalize();
            if (!directory.equals(file.getParent()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !directory.equals(file.toRealPath().getParent())) {
                return Optional.empty();
            }
            return Optional.of(new Download(file, entry.fileName(), contentType(entry.fileName()), Files.size(file)));
        } catch (IOException missing) {
            return Optional.empty();
        }
    }

    /** Forgets expired ids and deletes their files. */
    public void purge() {
        Instant now = clock.instant();
        entries.forEach((id, entry) -> {
            if (!now.isBefore(entry.expires())) expire(id, entry);
        });
    }

    private void expire(String id, Entry entry) {
        if (entries.remove(id, entry)) {
            try {
                Files.deleteIfExists(entry.directory().resolve(entry.fileName()));
            } catch (IOException ignored) {
                // A file still open by a running download is deleted by the next purge of its directory.
            }
        }
    }

    /** Deletes this chat's exports (and abandoned .part files) older than the retention, except {@code keep}. */
    private void deleteStale(Path directory, Path keep) {
        Instant limit = clock.instant().minus(retention);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "search-history-*")) {
            for (Path file : files) {
                if (file.equals(keep) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(limit)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException ignored) {
            // Cleanup is best effort; the export itself succeeded.
        }
    }

    static String contentType(String fileName) {
        if (fileName.endsWith(".zip")) return "application/zip";
        if (fileName.endsWith(".jsonl")) return "application/x-ndjson; charset=utf-8";
        return "text/plain; charset=utf-8";
    }
}
