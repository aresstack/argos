package com.aresstack.argos;

import com.aresstack.argos.network.NetworkSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import com.aresstack.argos.page.DocumentType;
import com.aresstack.argos.page.PageSnapshot;
import com.aresstack.argos.page.ReadinessReport;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CamoufoxBrowserTest {
    @TempDir Path temporary;

    @Test @Timeout(value=160,unit=TimeUnit.SECONDS)
    void navigatesInspectsAndInteractsThroughOnlyCamoufox() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        byte[] html = "<html><head><title>Argos smoke</title></head><body><h1 id='subject'>Browser research</h1><button id='click' onclick=\"document.querySelector('#subject').textContent='Clicked successfully'\">Click</button><input id='search'/></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer site = HttpServer.create(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(),
                temporary.resolve("profile"), true)
                // The smoke site is on loopback, which the strict network policy only admits by rule.
                .withNetwork(NetworkSettings.parse("strict", "127.0.0.1,::1", null, null)))) {
            String url = "http://localhost:" + site.getAddress().getPort() + "/";
            Map<String,Object> navigated = browser.navigate(url);
            assertEquals("Argos smoke", navigated.get("title"));
            assertTrue(browser.snapshot(8000).text().contains("Browser research"));
            assertFalse(browser.inspect("#subject", 5).isEmpty());
            assertTrue(browser.screenshot().length > 500);
            browser.fill("#search", "inspection");
            assertEquals("inspection", browser.evaluate(
                    "() => document.querySelector('#search').value"));
            browser.click("#click");
            assertEquals("Clicked successfully", browser.evaluate(
                    "() => document.querySelector('#subject').textContent"));
            assertFalse(browser.networkEvents().isEmpty());
            var web = new com.aresstack.argos.web.WebSession(browser,
                    com.aresstack.argos.web.UrlPolicy.allowing("127.0.0.1,::1"), null);
            assertTrue(web.open(url).contains("TITLE: Argos smoke"));
            assertTrue(web.read(1).contains("Browser research"));
        } finally {
            site.stop(0);
        }
    }

    @Test @Timeout(value=240,unit=TimeUnit.SECONDS)
    void readsEveryDocumentTypeAndWaitsForDynamicContent() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        try (FixtureServer site = new FixtureServer();
             CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                     "127.0.0.1", 0, local.executable(), local.launchOptions(),
                     temporary.resolve("profile"), true).withNetwork(TestNetworks.LOOPBACK))) {
            // A2: the SPA renders its results 1.2 s after DOMContentLoaded behind a spinner.
            Map<String,Object> spa = browser.navigate(site.url("/spa.html"));
            ReadinessReport readiness = (ReadinessReport) spa.get("readiness");
            assertEquals(ReadinessReport.READY, readiness.state(), readiness.toString());
            assertEquals(java.util.List.of("network-quiet"), readiness.met());
            Map<String,Object> waited = browser.waitFor(null, "second", 10_000);
            assertEquals(ReadinessReport.READY, ((ReadinessReport) waited.get("readiness")).state());
            assertTrue(browser.snapshot(8000).text().contains("Search results"));
            // A permanent error is reported as such, not as a readiness timeout.
            assertThrows(IllegalStateException.class, () -> browser.waitFor("div[", null, 5_000));

            try (CamoufoxBrowser thorough = new CamoufoxBrowser(new ArgosConfig(
                    "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("thorough"),
                    true, null, "none", null, "argos", ToolProfile.LEGACY,
                    com.aresstack.argos.page.ReadinessPolicy.thorough()).withNetwork(TestNetworks.LOOPBACK))) {
                ReadinessReport settled = (ReadinessReport) thorough.navigate(site.url("/spa.html")).get("readiness");
                assertEquals(ReadinessReport.READY, settled.state(), settled.toString());
                assertTrue(thorough.snapshot(8000).text().contains("Search results"), settled.toString());
                // Heavy DOM: 20 000 articles built in ten chunks; settles once the last chunk is in.
                ReadinessReport heavy = (ReadinessReport) thorough.navigate(site.url("/heavy.html")).get("readiness");
                assertEquals(ReadinessReport.READY, heavy.state(), heavy.toString());
                PageSnapshot big = thorough.snapshot(60_000);
                assertTrue(big.truncated() && big.text().contains("Result 0"), String.valueOf(big.characters()));
            }

            // A1: the Springer case — an RSS document has no HTML body.
            Map<String,Object> feed = browser.navigate(site.url("/springer-feed.rss"));
            assertEquals(DocumentType.FEED, feed.get("documentType"), feed.toString());
            PageSnapshot rss = browser.snapshot(8000);
            assertEquals(DocumentType.FEED, rss.documentType());
            assertTrue(rss.text().contains("1. Robust page readiness for research agents"), rss.text());

            browser.navigate(site.url("/sitemap.xml"));
            assertTrue(browser.snapshot(8000).text().contains("https://example.org/papers/1"));

            browser.navigate(site.url("/data.json"));
            PageSnapshot json = browser.snapshot(8000);
            assertEquals(DocumentType.JSON, json.documentType());
            assertTrue(json.text().contains("second"), json.text());

            browser.navigate(site.url("/notes.txt"));
            PageSnapshot text = browser.snapshot(8000);
            assertEquals(DocumentType.TEXT, text.documentType());
            assertTrue(text.text().contains("indented line two"), text.text());

            Map<String,Object> pdf = browser.navigate(site.url("/paper.pdf"));
            assertEquals(DocumentType.BINARY, pdf.get("documentType"), pdf.toString());
            assertEquals(DocumentType.BINARY, browser.snapshot(8000).documentType());
        }
    }
}
