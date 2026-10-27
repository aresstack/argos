package com.aresstack.argos;

import com.aresstack.argos.history.HistoryEvent;
import com.aresstack.argos.history.ResourceCategory;
import com.aresstack.argos.history.SearchHistory;
import com.aresstack.argos.history.SearchHistoryCapture;
import com.aresstack.argos.history.SearchHistoryExporter;
import com.aresstack.argos.web.PageCapture;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec Tests A-D and I in a real Camoufox (opt-in, -PtestCamoufox=true): a local server counts every request. A page
 * loads JSON by fetch() and XML by XHR plus CSS, JavaScript and an image. The Search History must store the default
 * content types including the dynamic data (searchable although not shown), store none of the optional types by
 * default, store an image after the chat switched images on, and cause NO additional request at the site: body reads
 * go over the protocol, recording/indexing/exporting never runs the HTML collector. Only browser_archive_html runs it.
 */
class SearchHistoryNetworkBrowserTest {
    @TempDir Path temporary;

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String PAGE = """
            <!DOCTYPE html><html><head><title>Netz-Test</title><link rel="stylesheet" href="/style.css">
            <script src="/app.js"></script></head><body><h1>Sichtbarer Text</h1><img src="/pixel.png" alt="p">
            <p id="state">loading</p>
            <script>
            Promise.all([
              fetch('/api/data.json').then(r => r.json()),
              new Promise(done => { const x = new XMLHttpRequest(); x.open('GET', '/api/feed.xml');
                x.onload = () => done(x.responseText); x.send(); })
            ]).then(() => { document.getElementById('state').textContent = 'loaded'; });
            </script></body></html>""";
    private static final String IMAGES_PAGE = """
            <!DOCTYPE html><html><head><title>Bilder</title></head><body><img src="/second.png" alt="s">
            <p>Bildseite</p></body></html>""";

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void storesReceivedContentWithoutAnyAdditionalRequest() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        Map<String, byte[]> files = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        serve(files, types, "/", PAGE.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        serve(files, types, "/images.html", IMAGES_PAGE.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        serve(files, types, "/style.css", "h1 { color: rgb(1, 2, 3); } /* cssmarker */".getBytes(), "text/css");
        serve(files, types, "/app.js", "var jsmarker = 1;".getBytes(), "text/javascript");
        serve(files, types, "/pixel.png", PNG, "image/png");
        serve(files, types, "/second.png", PNG, "image/png");
        serve(files, types, "/api/data.json", "{\"items\":[{\"name\":\"quasarwidget\"}]}".getBytes(),
                "application/json; charset=utf-8");
        serve(files, types, "/api/feed.xml", "<feed><entry>nebulagadget</entry></feed>".getBytes(), "application/xml");
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            byte[] body = files.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", types.get(path));
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            String base = "http://localhost:" + site.getAddress().getPort();
            SearchHistory history = new SearchHistory(temporary.resolve("chat").resolve("history"));
            AtomicInteger evaluations = new AtomicInteger();
            SearchHistoryCapture capture = new SearchHistoryCapture(history, new SearchHistoryCapture.LivePages() {
                @Override public PageCapture capture(String tabId) { return browser.capturePage(tabId); }
                @Override public Object evaluate(String function, String tabId) {
                    evaluations.incrementAndGet();
                    return browser.evaluate(function, tabId);
                }
            }, 10_000);
            // Diagnostics: every offered response with its metadata and the outcome, written to
            // build/search-history-network.txt and included in assertion messages.
            List<String> trace = java.util.Collections.synchronizedList(new ArrayList<>());
            com.aresstack.argos.network.ResponseContentSink real = capture.networkContent();
            browser.contentSink(new com.aresstack.argos.network.ResponseContentSink() {
                @Override public long wants(ObservedResponse r) {
                    long wanted = real.wants(r);
                    trace.add("offered " + r.url() + " status=" + r.status() + " type=" + r.resourceType()
                            + " mime=" + r.mimeType() + " length=" + r.contentLength() + " encoded=" + r.encoded()
                            + " -> " + (wanted > 0 ? "wanted" : "skipped"));
                    return wanted;
                }
                @Override public void deliver(ObservedResponse r, byte[] body, String unavailable) {
                    trace.add("read " + r.url() + " -> " + (body == null ? "unavailable: " + unavailable
                            : body.length + " bytes"));
                    real.deliver(r, body, unavailable);
                }
            });

            browser.navigate(base + "/");
            browser.waitFor(null, "loaded", 10_000);
            capture.browserPage(null, base + "/", true, "browser_navigate");
            browser.snapshot(4_000); // a further browser task: queued bodies of late responses are read after it
            capture.browserPage(null, null, false, "browser_snapshot");
            SearchHistoryCapture.awaitWriter();
            Map<String, Integer> afterBrowsing = counts(requests);

            // Test C: dynamic data is searchable although it is not part of the visible snapshot.
            assertFalse(history.search("quasarwidget", 5).isEmpty(), String.join("\n", new ArrayList<>(trace)));
            assertEquals("resource", history.search("quasarwidget", 5).get(0).type());
            assertEquals("resource", history.search("nebulagadget", 5).get(0).type());
            assertFalse(browser.capturePage(null).text().contains("quasarwidget"));
            // Test A/B: the document and the data are stored, the optional types are not.
            List<String> categories = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            history.repository().forEach(0, event -> {
                if (event instanceof HistoryEvent.NetworkResource resource) {
                    categories.add(resource.category());
                    urls.add(resource.url());
                }
            });
            Path report = Path.of("build", "search-history-network.txt");
            Files.createDirectories(report.getParent());
            Files.write(report, new ArrayList<>(trace));
            String why = "stored=" + urls + "\ntrace (also in " + report.toAbsolutePath() + "):\n"
                    + String.join("\n", new ArrayList<>(trace));
            assertTrue(urls.contains(base + "/api/data.json"), why);
            assertTrue(urls.contains(base + "/api/feed.xml"), why);
            assertTrue(urls.contains(base + "/"), "the page's HTML document: " + why);
            assertTrue(categories.stream().allMatch("content"::equals), categories.toString());
            assertTrue(history.search("cssmarker", 5).isEmpty() && history.search("jsmarker", 5).isEmpty());

            // Test D: indexing and every export cause no request and run no collector.
            for (SearchHistoryExporter.Format format : SearchHistoryExporter.Format.values()) {
                new SearchHistoryExporter(history.repository()).export(format, false, true);
            }
            history.rebuildIndex();
            assertEquals(afterBrowsing, counts(requests), "the history caused no additional request");
            assertEquals(0, evaluations.get(), "no HtmlCollector.evaluate() during normal operations");
            for (Map.Entry<String, Integer> entry : afterBrowsing.entrySet()) {
                if (!entry.getKey().equals("/favicon.ico")) {
                    assertEquals(1, entry.getValue(), "requested once by the page itself: " + entry.getKey());
                }
            }

            // Optional category switched on for this chat: only future responses, only images.
            history.captureSettings().set(Map.of(ResourceCategory.IMAGES, true));
            browser.navigate(base + "/images.html");
            browser.snapshot(2_000);
            SearchHistoryCapture.awaitWriter();
            List<HistoryEvent.NetworkResource> images = new ArrayList<>();
            history.repository().forEach(0, event -> {
                if (event instanceof HistoryEvent.NetworkResource resource && resource.category().equals("images")) {
                    images.add(resource);
                }
            });
            assertEquals(1, images.size(), "only the new page's image, the earlier one is not fetched again");
            assertEquals(base + "/second.png", images.get(0).url());
            assertArrayEquals(PNG, Files.readAllBytes(history.repository().resourceBody(images.get(0).resourceId())));
            assertEquals(1, requests.get("/pixel.png").get());

            // Test I: the explicit tool runs the collector exactly once.
            browser.navigate(base + "/");
            browser.waitFor(null, "loaded", 10_000);
            SearchHistoryCapture.HtmlArchiveResult archived = capture.archiveHtml(null);
            assertEquals(1, evaluations.get());
            assertTrue(archived.html().contains("Sichtbarer Text"));
            assertTrue(history.repository().hasHtml(archived.snapshotId()));
        } finally {
            site.stop(0);
        }
    }

    private static void serve(Map<String, byte[]> files, Map<String, String> types, String path, byte[] body,
                              String type) {
        files.put(path, body);
        types.put(path, type);
    }

    private static Map<String, Integer> counts(Map<String, AtomicInteger> requests) {
        Map<String, Integer> copy = new HashMap<>();
        requests.forEach((path, count) -> copy.put(path, count.get()));
        return copy;
    }
}
