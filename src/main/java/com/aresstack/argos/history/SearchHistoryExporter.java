package com.aresstack.argos.history;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports one chat's history from its canonical data (never from the Lucene index) into {@code history/exports/}:
 * a URL list ({@code .txt}), every event with readable content ({@code .jsonl}) and the single-file HTML archive
 * ({@code .zip}). Every export streams the event log; only small per-snapshot metadata is held in memory. File names
 * are chosen here, never taken from model arguments.
 */
public final class SearchHistoryExporter {
    public enum Format { TXT, JSONL, ZIP }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SearchHistoryRepository repository;

    public SearchHistoryExporter(SearchHistoryRepository repository) {
        this.repository = repository;
    }

    /** Where an export went and what it holds. */
    public record Export(Path file, int records) { }

    public static Format format(String value) {
        String wanted = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (wanted) {
            case "txt", "urls", "url" -> Format.TXT;
            case "jsonl", "json" -> Format.JSONL;
            case "zip", "html" -> Format.ZIP;
            default -> throw new IllegalArgumentException("Unknown export format \"" + abbreviate(value)
                    + "\"; use txt, jsonl or zip.");
        };
    }

    /**
     * Writes the export to a new file under {@code history/exports/}.
     *
     * @param unique         txt: each URL once, in the order of its first visit
     * @param includeResults txt: also the URLs of search results (default: only visited pages)
     */
    public Export export(Format format, boolean unique, boolean includeResults) throws IOException {
        Path file = newExportFile(format.name().toLowerCase(Locale.ROOT));
        Path temporary = file.resolveSibling(file.getFileName() + ".part");
        int records;
        try (OutputStream out = Files.newOutputStream(temporary)) {
            records = switch (format) {
                case TXT -> writeUrls(out, unique, includeResults);
                case JSONL -> writeJsonl(out);
                case ZIP -> writeZip(out);
            };
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(temporary);
            throw failed;
        }
        Files.move(temporary, file);
        return new Export(file, records);
    }

    private Path newExportFile(String extension) throws IOException {
        Path directory = repository.exportsDirectory();
        Files.createDirectories(directory);
        String stem = "search-history-" + STAMP.format(Instant.now());
        Path file = directory.resolve(stem + "." + extension);
        for (int i = 1; Files.exists(file); i++) file = directory.resolve(stem + "-" + i + "." + extension);
        return file;
    }

    // ------------------------------------------------------------------ txt

    int writeUrls(OutputStream out, boolean unique, boolean includeResults) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        Set<String> seen = new HashSet<>();
        int[] count = {0};
        repository.forEach(0, event -> {
            List<String> urls = new ArrayList<>();
            switch (event) {
                case HistoryEvent.PageVisit visit -> urls.add(visit.url());
                case HistoryEvent.Search search -> {
                    if (includeResults) search.results().forEach(result -> urls.add(result.url()));
                }
            }
            for (String url : urls) {
                if (url == null || url.isBlank() || (unique && !seen.add(url))) continue;
                writer.write(url.replace("\r", "").replace("\n", ""));
                writer.write('\n');
                count[0]++;
            }
        });
        writer.flush();
        return count[0];
    }

    // ------------------------------------------------------------------ jsonl

    int writeJsonl(OutputStream out) throws IOException {
        int[] count = {0};
        JsonGenerator json = JSON.getFactory().createGenerator(out, JsonEncoding.UTF8);
        json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        repository.forEach(0, event -> {
            json.writeStartObject();
            switch (event) {
                case HistoryEvent.Search search -> {
                    json.writeStringField("type", "search");
                    json.writeStringField("query", search.query());
                    json.writeStringField("searchedAt", search.at().toString());
                    json.writeStringField("provider", search.provider());
                    json.writeStringField("contextId", search.contextId());
                    json.writeArrayFieldStart("results");
                    for (HistoryEvent.Result result : search.results()) {
                        json.writeStartObject();
                        json.writeNumberField("rank", result.rank());
                        json.writeStringField("title", result.title());
                        json.writeStringField("url", result.url());
                        json.writeStringField("snippet", result.snippet());
                        json.writeEndObject();
                    }
                    json.writeEndArray();
                }
                case HistoryEvent.PageVisit visit -> {
                    StoredPage page = repository.page(visit.snapshotId()).orElse(null);
                    json.writeStringField("type", "page_visit");
                    json.writeStringField("url", visit.url());
                    json.writeStringField("requestedUrl", visit.requestedUrl());
                    json.writeStringField("title", visit.title());
                    json.writeStringField("visitedAt", visit.at().toString());
                    json.writeNumberField("status", visit.status());
                    json.writeStringField("content", page == null ? null : page.content());
                    json.writeArrayFieldStart("links");
                    if (page != null) for (String link : page.links()) json.writeString(link);
                    json.writeEndArray();
                    json.writeStringField("contextId", visit.contextId());
                    json.writeStringField("contentHash", visit.contentHash());
                    json.writeStringField("snapshotId", visit.snapshotId());
                    json.writeBooleanField("htmlArchived", repository.hasHtml(visit.snapshotId()));
                    json.writeStringField("source", visit.source());
                }
            }
            json.writeEndObject();
            json.writeRaw('\n');
            count[0]++;
        });
        json.flush();
        return count[0];
    }

    // ------------------------------------------------------------------ zip

    /** Table-of-contents row of one distinct snapshot (metadata only, never its content). */
    private record Row(String file, String title, String url, Instant firstVisit, int visits, int status,
                       boolean fallback) { }

    int writeZip(OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        Map<String, Row> rows = new LinkedHashMap<>();
        Set<String> names = new HashSet<>(Set.of("index.html"));
        repository.forEach(0, event -> {
            if (!(event instanceof HistoryEvent.PageVisit visit)) return;
            Row known = rows.get(visit.snapshotId());
            if (known != null) {
                rows.put(visit.snapshotId(), new Row(known.file(), known.title(), known.url(), known.firstVisit(),
                        known.visits() + 1, known.status(), known.fallback()));
                return;
            }
            StoredPage page = repository.page(visit.snapshotId()).orElse(null);
            String title = page != null && !page.title().isBlank() ? page.title()
                    : visit.title() == null ? "" : visit.title();
            String name = uniqueName(names, fileStem(title, visit.url()));
            boolean archived = repository.hasHtml(visit.snapshotId());
            zip.putNextEntry(new ZipEntry(name));
            if (archived) {
                Files.copy(repository.htmlFile(visit.snapshotId()), zip);
            } else {
                zip.write(fallbackHtml(page, visit).getBytes(StandardCharsets.UTF_8));
            }
            zip.closeEntry();
            rows.put(visit.snapshotId(), new Row(name, title, visit.url(), visit.at(), 1, visit.status(), !archived));
        });
        zip.putNextEntry(new ZipEntry("index.html"));
        zip.write(indexHtml(rows.values()).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        return rows.size();
    }

    /** As cam-tool's PageArchiver::sanitizeFileName plus a safe character set: never a path from a title. */
    static String fileStem(String title, String url) {
        String stem = sanitize(title);
        if (stem.isEmpty()) {
            try {
                stem = sanitize(java.net.URI.create(url).getHost());
            } catch (RuntimeException ignored) {
                stem = "";
            }
        }
        if (stem.isEmpty()) stem = "Seite";
        if (RESERVED.contains(stem.toLowerCase(Locale.ROOT)) || stem.equalsIgnoreCase("index")) stem = "_" + stem;
        return stem;
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder();
        name.codePoints().forEach(c -> out.appendCodePoint(
                Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.' ? c : '-'));
        // Runs of dots and dashes ("..", "-.-") collapse, so nothing like a parent-directory step survives.
        String stem = out.toString().replaceAll("\\s+", " ").replaceAll("[-.]{2,}", "-");
        if (stem.length() > 80) stem = stem.substring(0, 80);
        return stem.replaceAll("^[-. ]+|[-. ]+$", "");
    }

    /** {@code stem.html}, else {@code stem-1.html}, {@code stem-2.html}, ... (as cam-tool's FavoritesStore::add). */
    static String uniqueName(Set<String> used, String stem) {
        String name = stem + ".html";
        for (int i = 1; used.contains(name.toLowerCase(Locale.ROOT)); i++) name = stem + "-" + i + ".html";
        used.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    private static String fallbackHtml(StoredPage page, HistoryEvent.PageVisit visit) {
        String title = page != null ? page.title() : visit.title();
        String reason = page != null && page.htmlNote() != null ? page.htmlNote() : "No single-file HTML snapshot.";
        StringBuilder html = new StringBuilder("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>")
                .append(escape(title)).append("</title></head><body>\n")
                .append("<p style=\"border:2px solid #b00;padding:8px\"><strong>Textbasierter Fallback</strong>: ")
                .append("kein vollständiger HTML-Snapshot dieser Seite. ").append(escape(reason)).append("</p>\n")
                .append("<h1>").append(escape(title)).append("</h1>\n<p><a href=\"").append(escape(visit.url()))
                .append("\">").append(escape(visit.url())).append("</a></p>\n<pre style=\"white-space:pre-wrap\">")
                .append(escape(page == null ? "" : page.content())).append("</pre>\n");
        if (page != null && !page.links().isEmpty()) {
            html.append("<h2>Links</h2>\n<ul>\n");
            for (String link : page.links()) {
                html.append("<li><a href=\"").append(escape(link)).append("\">").append(escape(link))
                        .append("</a></li>\n");
            }
            html.append("</ul>\n");
        }
        return html.append("</body></html>\n").toString();
    }

    private static String indexHtml(Iterable<Row> rows) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
                + "<title>Argos Search History</title><style>body{font-family:sans-serif;margin:24px}"
                + "table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px 8px;text-align:left}"
                + "</style></head><body>\n<h1>Argos Search History</h1>\n<table>\n"
                + "<tr><th>Seite</th><th>Ursprüngliche URL</th><th>Besucht (UTC)</th><th>Besuche</th>"
                + "<th>Status</th><th>Archiv</th></tr>\n");
        for (Row row : rows) {
            html.append("<tr><td><a href=\"").append(escape(row.file())).append("\">")
                    .append(escape(row.title().isBlank() ? row.file() : row.title())).append("</a></td><td><a href=\"")
                    .append(escape(row.url())).append("\">").append(escape(row.url())).append("</a></td><td>")
                    .append(row.firstVisit()).append("</td><td>").append(row.visits()).append("</td><td>")
                    .append(row.status() == 0 ? "unbekannt" : Integer.toString(row.status())).append("</td><td>")
                    .append(row.fallback() ? "Textbasierter Fallback" : "Single-File-HTML").append("</td></tr>\n");
        }
        return html.append("</table>\n</body></html>\n").toString();
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String abbreviate(String value) {
        String text = value == null ? "" : value;
        return text.length() <= 20 ? text : text.substring(0, 20) + "…";
    }
}
