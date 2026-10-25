package com.aresstack.argos;

import com.aresstack.argos.page.DocumentType;
import com.aresstack.argos.page.ReadinessReport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in ({@code -PtestCamoufox=true}): Firefox saves feeds as downloads. Argos reads a finished one from the saved
 * file, never waits for an unfinished one on the driver thread, and keeps back, reload and controls consistent.
 */
class SavedFeedCamoufoxTest {
    @TempDir Path temporary;

    private static final String PAGE = "<html><head><title>Start</title></head><body><button>Weiter</button></body></html>";
    private static final String FEED = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss version=\"2.0\"><channel>"
            + "<title>Grüße</title><item><title>Eintrag %d</title><link>https://example.org/1</link></item></channel></rss>";

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void savedFeedsAreReadOnlyWhenFinishedAndHistoryStaysConsistent() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        AtomicInteger feedHits = new AtomicInteger();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.createContext("/feed.rss", exchange -> {
            // No charset in the header: the XML declaration says ISO-8859-1.
            byte[] body = FEED.formatted(feedHits.incrementAndGet()).getBytes(StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.getResponseHeaders().add("Cache-Control", "no-store"); // reload must reach the server
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.createContext("/endless.rss", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=utf-8");
            exchange.sendResponseHeaders(200, 0); // chunked, and the body never ends
            OutputStream out = exchange.getResponseBody();
            out.write("<?xml version=\"1.0\"?><rss><channel>".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                // server stopped
            }
            exchange.close();
        });
        site.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        site.start();
        String base = "http://localhost:" + site.getAddress().getPort();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            browser.navigate(base + "/");

            Map<String, Object> feed = browser.navigate(base + "/feed.rss");
            assertEquals(DocumentType.FEED, feed.get("documentType"), feed.toString());
            String text = browser.snapshot(8000).text();
            assertTrue(text.contains("Grüße") && text.contains("Eintrag 1"), text);

            // Reload fetches the feed again; back returns to the page shown before it.
            browser.reload();
            assertTrue(browser.snapshot(8000).text().contains("Eintrag 2"));

            // Every tool reports the feed while it is shown, and a failed call does not leave it.
            assertEquals(base + "/feed.rss", browser.currentUrl());
            assertEquals(base + "/feed.rss", browser.tabs().get(0).get("url"));
            Object found = browser.waitFor(null, "eintrag   2", 1_000).get("readiness");
            assertTrue(((ReadinessReport) found).ready(), String.valueOf(found));
            assertThrows(RuntimeException.class, () -> browser.clickTarget("Nichts dergleichen"));
            assertThrows(RuntimeException.class, browser::forward);
            assertEquals(DocumentType.FEED, browser.snapshot(8000).documentType());
            Map<String, Object> back = browser.historyBack();
            assertEquals("Start", back.get("title"), back.toString());
            assertEquals(DocumentType.HTML, browser.snapshot(8000).documentType());

            // Listing controls acts on the rendered page and leaves the feed.
            browser.navigate(base + "/feed.rss");
            assertTrue(browser.controls().all().stream().anyMatch(c -> c.name().equals("Weiter")));
            assertEquals(DocumentType.HTML, browser.snapshot(8000).documentType());

            // A feed whose body never ends stays a download, without holding the driver thread.
            long started = System.nanoTime();
            Map<String, Object> endless = browser.navigate(base + "/endless.rss");
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            assertEquals(DocumentType.BINARY, endless.get("documentType"), endless.toString());
            assertTrue(elapsed < 15_000, "took " + elapsed + " ms");
            assertFalse(browser.navigate(base + "/").get("title").toString().isEmpty());
        } finally {
            site.stop(0);
        }
    }
}
