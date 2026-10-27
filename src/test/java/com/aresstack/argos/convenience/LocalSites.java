package com.aresstack.argos.convenience;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlled local web servers for the convenience tests: every public URL (https://www.bing.com/search?q=x) is
 * served from 127.0.0.1 under /{host}/{path}. Each page has a status, body, delay and headers; the server logs
 * the interval of every request (to prove overlap or serialization) and the highest number of requests in flight.
 */
final class LocalSites implements AutoCloseable {
    record Page(int status, String body, long delayMillis, Map<String, String> headers) {
    }

    record Hit(String host, String path, long start, long end) {
        boolean overlaps(Hit other) {
            return start < other.end && other.start < end;
        }
    }

    private final HttpServer server;
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    final List<Hit> hits = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();

    LocalSites() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
        server.createContext("/", exchange -> {
            long start = System.nanoTime();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            String raw = exchange.getRequestURI().getRawPath();
            int slash = raw.indexOf('/', 1);
            String host = slash < 0 ? raw.substring(1) : raw.substring(1, slash);
            String path = slash < 0 ? "/" : raw.substring(slash);
            Page page = pages.getOrDefault(host + path,
                    new Page(404, "<html><head><title>Not found</title></head><body>404</body></html>", 0, Map.of()));
            try {
                if (page.delayMillis() > 0) Thread.sleep(page.delayMillis());
                byte[] body = page.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                page.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                exchange.sendResponseHeaders(page.status(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
                hits.add(new Hit(host, path, start, System.nanoTime()));
                exchange.close();
            }
        });
        server.start();
    }

    /** Serves {@code url}'s host and path (the query is ignored). */
    LocalSites page(String url, int status, String body, long delayMillis, Map<String, String> headers) {
        URI uri = URI.create(url);
        pages.put(uri.getHost() + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath()),
                new Page(status, body, delayMillis, headers));
        return this;
    }

    LocalSites page(String url, String body) {
        return page(url, 200, body, 0, Map.of());
    }

    LocalSites fixture(String url, String resource, long delayMillis) {
        try (InputStream in = LocalSites.class.getResourceAsStream("/serp/" + resource)) {
            return page(url, 200, new String(in.readAllBytes(), StandardCharsets.UTF_8), delayMillis, Map.of());
        } catch (IOException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** Where the double fetches {@code url} from. */
    String local(String url) {
        URI uri = URI.create(url);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + uri.getHost() + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    List<Hit> hits(String host) {
        synchronized (hits) {
            return hits.stream().filter(hit -> hit.host().equals(host)).toList();
        }
    }

    List<Hit> hitsOfDomain(String registrable) {
        synchronized (hits) {
            return hits.stream().filter(hit -> RegistrableDomain.ofHost(hit.host()).equals(registrable)).toList();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
