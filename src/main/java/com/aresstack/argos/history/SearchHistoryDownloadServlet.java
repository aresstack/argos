package com.aresstack.argos.history;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Optional;

/**
 * {@code GET /api/search-history/exports/{exportId}}: streams one export as an attachment. Unknown, expired and
 * malformed ids all answer 404 alike, so the endpoint reveals nothing about other ids or files.
 */
public final class SearchHistoryDownloadServlet extends HttpServlet {
    private final SearchHistoryDownloads downloads;

    public SearchHistoryDownloadServlet(SearchHistoryDownloads downloads) {
        this.downloads = downloads;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        String id = path == null ? null : path.startsWith("/") ? path.substring(1) : path;
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        Optional<SearchHistoryDownloads.Download> found = downloads.resolve(id);
        if (found.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain; charset=utf-8");
            response.getOutputStream().write("Export not found or expired.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        SearchHistoryDownloads.Download download = found.get();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(download.contentType());
        response.setContentLengthLong(download.size());
        // File names are Argos-made ASCII (search-history-<time>.<ext>, page-archive-<time>.html): no escaping needed.
        response.setHeader("Content-Disposition", "attachment; filename=\"" + download.fileName() + "\"");
        try (InputStream in = Files.newInputStream(download.file())) {
            in.transferTo(response.getOutputStream());
        }
    }
}
