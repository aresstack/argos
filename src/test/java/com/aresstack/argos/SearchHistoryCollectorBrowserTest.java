package com.aresstack.argos;

import com.aresstack.argos.history.HtmlCollector;
import com.aresstack.argos.history.SearchHistory;
import com.aresstack.argos.history.SearchHistoryCapture;
import com.aresstack.argos.web.PageCapture;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §10 item 6 in a real Camoufox (opt-in, -PtestCamoufox=true): a local page with an image, an external
 * stylesheet with a CSS background image, a script and relative links. The archived single file must embed the
 * reachable resources, contain no script, have absolute links, leave no globals in the page and create no
 * resource folder.
 */
class SearchHistoryCollectorBrowserTest {
    @TempDir Path temporary;

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String PAGE = """
            <!DOCTYPE html><html><head><title>Archiv-Test</title>
            <link rel="stylesheet" href="/style.css"><link rel="preload" href="/late.js" as="script">
            </head><body><h1>Archiv</h1><img src="/pixel.png" alt="pixel">
            <img src="/missing.png" alt="missing">
            <a href="/next">weiter</a> <a href="sub/page.html">unten</a>
            <form><input type="password" name="pw" value="geheim"></form>
            <script>window.addedByPage = 1;</script></body></html>""";
    private static final String CSS = "body { background-image: url('/bg.png'); } h1 { color: rgb(1, 2, 3); }";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void archivesTheRenderedPageAsOneSelfContainedFile() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        Map<String, byte[]> files = Map.of("/", PAGE.getBytes(StandardCharsets.UTF_8),
                "/style.css", CSS.getBytes(StandardCharsets.UTF_8), "/pixel.png", PNG, "/bg.png", PNG);
        Map<String, String> types = Map.of("/", "text/html; charset=utf-8", "/style.css", "text/css",
                "/pixel.png", "image/png", "/bg.png", "image/png");
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = files.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", types.get(path));
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            String base = "http://localhost:" + site.getAddress().getPort();
            browser.navigate(base + "/");
            String globals = "() => Object.keys(window).filter(k => !/^(addedByPage)$/.test(k)).length";
            Object before = browser.evaluate(globals);

            HtmlCollector.Collected collected = HtmlCollector.collect(
                    function -> browser.evaluate(function, null), 10_000).orElseThrow();
            assertEquals(before, browser.evaluate(globals), "the collector leaves no global behind");
            assertEquals("Archiv-Test", collected.title());

            Document archived = Jsoup.parse(collected.html(), "");
            assertTrue(archived.select("script").isEmpty(), "scripts removed");
            assertTrue(archived.select("link[rel~=preload]").isEmpty(), "preload links removed");
            assertTrue(archived.select("link[rel~=stylesheet]").isEmpty(), "stylesheet inlined");
            String style = archived.select("style[data-from]").text();
            assertTrue(style.contains("rgb(1, 2, 3)"), style);
            assertTrue(style.contains("url('data:image/png;base64,"), "CSS background image embedded: " + style);
            assertTrue(archived.select("img[alt=pixel]").attr("src").startsWith("data:image/png;base64,"));
            assertEquals("/missing.png", archived.select("img[alt=missing]").attr("src"),
                    "an unreachable resource stays an external reference");
            assertEquals(base + "/next", archived.select("a").get(0).attr("href"));
            assertEquals(base + "/sub/page.html", archived.select("a").get(1).attr("href"));

            // The same page through the Search History: one .json and one .html, no resource folders.
            SearchHistory history = new SearchHistory(temporary.resolve("chat").resolve("history"));
            SearchHistoryCapture capture = new SearchHistoryCapture(history, new SearchHistoryCapture.LivePages() {
                @Override public PageCapture capture(String tabId) { return browser.capturePage(tabId); }
                @Override public Object evaluate(String function, String tabId) { return browser.evaluate(function, tabId); }
            }, true, 10_000);
            capture.browserPage(null, base + "/", true, "browser_navigate");
            try (Stream<Path> stored = Files.list(temporary.resolve("chat").resolve("history").resolve("pages"))) {
                List<Path> pages = stored.toList();
                assertEquals(2, pages.size(), pages.toString());
                assertTrue(pages.stream().noneMatch(Files::isDirectory));
                Path html = pages.stream().filter(p -> p.toString().endsWith(".html")).findFirst().orElseThrow();
                String saved = Files.readString(html);
                assertFalse(saved.contains("geheim"), "form values are not archived");
                assertTrue(saved.contains("data:image/png;base64,"));
            }
        } finally {
            site.stop(0);
        }
    }
}
