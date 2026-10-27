package com.aresstack.argos;

import com.aresstack.argos.history.SearchHistoryDownloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The export download shares the MCP server; the bearer filter keeps guarding the MCP root. */
class SearchHistoryDownloadEndpointTest {
    @TempDir Path chat;

    @Test void downloadIsStreamedAsAttachmentWhileMcpStaysProtected() throws Exception {
        ArgosConfig config = new ArgosConfig("127.0.0.1", 0, null, null, chat.resolve("profile"), true,
                null, "central-oauth", "https://issuer.invalid", "argos");
        try (ArgosApplication.RunningServer running = ArgosApplication.serve(config, List.of())) {
            Path exports = Files.createDirectories(chat.resolve("history/exports"));
            Path file = Files.writeString(exports.resolve("search-history-20261027-060000.txt"), "https://a.example/\n");
            SearchHistoryDownloads.Link link = SearchHistoryDownloads.shared().register(exports, file);
            assertEquals("http://127.0.0.1:" + running.port() + SearchHistoryDownloads.PATH + link.id(), link.url());
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> download = client.send(HttpRequest.newBuilder(URI.create(link.url())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, download.statusCode());
            assertEquals("https://a.example/\n", download.body());
            assertEquals("attachment; filename=\"search-history-20261027-060000.txt\"",
                    download.headers().firstValue("Content-Disposition").orElse(""));
            assertTrue(download.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));

            String base = "http://127.0.0.1:" + running.port();
            for (String path : List.of(SearchHistoryDownloads.PATH + "unknown",
                    SearchHistoryDownloads.PATH + "..%2F..%2Fetc%2Fpasswd")) {
                int status = client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode();
                assertTrue(status == 404 || status == 400, path + " -> " + status); // Jetty itself refuses %2F

            }
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
