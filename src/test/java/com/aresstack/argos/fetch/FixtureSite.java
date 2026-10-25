package com.aresstack.argos.fetch;

import com.aresstack.argos.document.TestDocuments;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP fixtures for document and batch tests. Requests are served concurrently (the JDK server's default
 * executor would serialise them and hide any browser-side parallelism).
 */
public final class FixtureSite implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final byte[] pdf;
    private final byte[] docx;

    public FixtureSite() throws IOException {
        pdf = TestDocuments.pdf("Argos fixture paper about background downloads");
        docx = TestDocuments.docx("Bericht", "Inhalt des Berichts");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * Fixture address on 127.0.0.1, which test URL policies admit as an intranet network. The literal avoids
     * {@code localhost}, which Windows resolves to ::1 first: each connection then waits about 2 s for the
     * refused IPv6 attempt, which distorted the Camoufox measurement on Windows.
     */
    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** A loopback address the test URL policies do not admit (nothing has to listen there). */
    public String loopbackUrl(String path) {
        return "http://127.0.0.2:" + server.getAddress().getPort() + path;
    }

    /** URL policy for tests: only 127.0.0.1 is admitted, the rest of loopback stays blocked. */
    public static com.aresstack.argos.web.UrlPolicy testPolicy() {
        return new com.aresstack.argos.web.UrlPolicy(false, java.util.List.of("127.0.0.1/32"));
    }

    /** An address on which nothing listens (connection refused). */
    public static String refusedUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/";
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        int delay = delayOf(exchange.getRequestURI().getQuery());
        try {
            switch (path) {
                case "/article.html" -> send(exchange, 200, "text/html; charset=utf-8",
                        html("Article", "<h1>Research article</h1><a href='paper.pdf'>Full text (PDF)</a>"));
                case "/other.html" -> send(exchange, 200, "text/html; charset=utf-8",
                        html("Other", "<p>Another page</p>"));
                case "/paper.pdf" -> send(exchange, 200, "application/pdf", pdf);
                // A landing page that starts its file download a moment after loading.
                case "/download-soon.html" -> send(exchange, 200, "text/html; charset=utf-8", html("Download",
                        "<p>Your download starts shortly</p><script>setTimeout(() => location.href = 'attachment', "
                                + Math.max(delay, 1) + ")</script>"));
                case "/attachment" -> {
                    exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"bericht.docx\"");
                    send(exchange, 200, "application/octet-stream", docx);
                }
                case "/feed.xml" -> send(exchange, 200, "application/rss+xml", ("<?xml version=\"1.0\"?><rss><channel>"
                        + "<item><title>Feed item</title></item></channel></rss>").getBytes(StandardCharsets.UTF_8));
                case "/large.bin" -> send(exchange, 200, "application/octet-stream", new byte[64 * 1024]);
                case "/slow-head" -> {
                    Thread.sleep(delay);
                    send(exchange, 200, "text/html; charset=utf-8", html("Slow head", "<p>late headers</p>"));
                }
                case "/slow-body" -> slowBody(exchange, delay);
                case "/slow-attachment" -> {
                    exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"slow.docx\"");
                    exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, docx.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(docx, 0, docx.length / 2);
                        out.flush();
                        Thread.sleep(delay);
                        out.write(docx, docx.length / 2, docx.length - docx.length / 2);
                    }
                }
                case "/redirect" -> {
                    String query = exchange.getRequestURI().getRawQuery();
                    exchange.getResponseHeaders().add("Location", java.net.URLDecoder.decode(
                            query.substring(query.indexOf("to=") + 3), StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(302, -1);
                }
                case "/stalled-attachment" -> stall(exchange, "application/octet-stream", true);
                case "/stalled.pdf" -> stall(exchange, "application/pdf", false);
                default -> send(exchange, 404, "text/html", html("Not found", "<p>missing</p>"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    /** Headers and the first part at once, the rest of the document after {@code delay} ms. */
    private static void slowBody(HttpExchange exchange, int delay) throws IOException, InterruptedException {
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(("<html><head><title>Slow body</title></head><body><p>start</p>" + " ".repeat(2048))
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(delay);
            out.write("<p>end of slow body</p></body></html>".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Sends headers and a first chunk, then stops sending without closing (until the server stops). */
    private static void stall(HttpExchange exchange, String type, boolean attachment)
            throws IOException, InterruptedException {
        if (attachment) exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"big.bin\"");
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write(("%PDF-1.7\n" + "x".repeat(8192)).getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(120_000);
    }

    private static int delayOf(String query) {
        if (query == null) return 0;
        for (String part : query.split("&")) {
            if (part.startsWith("ms=")) return Integer.parseInt(part.substring(3));
        }
        return 0;
    }

    private static byte[] html(String title, String body) {
        return ("<!doctype html><html><head><title>" + title + "</title></head><body>" + body + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
