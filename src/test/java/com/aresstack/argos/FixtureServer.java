package com.aresstack.argos;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

/** Local HTTP server serving src/test/resources/fixtures with explicit Content-Type headers. */
public final class FixtureServer implements AutoCloseable {
    public static final Map<String, String> CONTENT_TYPES = Map.of(
            "/page.html", "text/html; charset=utf-8",
            "/spa.html", "text/html; charset=utf-8",
            "/springer-feed.rss", "application/rss+xml; charset=utf-8",
            "/atom.xml", "application/atom+xml",
            "/sitemap.xml", "application/xml",
            "/data.json", "application/json",
            "/notes.txt", "text/plain; charset=utf-8",
            "/paper.pdf", "application/pdf");
    private final HttpServer server;

    public FixtureServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String type = CONTENT_TYPES.get(path);
            byte[] body = type == null ? null : fixture(path.substring(1));
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public static byte[] fixture(String name) {
        try (InputStream in = FixtureServer.class.getResourceAsStream("/fixtures/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
