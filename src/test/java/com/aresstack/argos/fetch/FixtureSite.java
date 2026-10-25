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

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
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
