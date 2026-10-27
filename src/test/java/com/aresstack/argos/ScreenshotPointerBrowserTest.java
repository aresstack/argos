package com.aresstack.argos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: points read from a screenshot drive browser_mouse_click, also after the page was scrolled. */
class ScreenshotPointerBrowserTest {
    @TempDir Path temporary;

    private static final String PAGE = """
            <html><head><title>Ziele</title><style>
              body { margin: 0; } button { position: absolute; border: 0; padding: 0; }
              #a { left: 300px; top: 200px; width: 90px; height: 40px; background: red; }
              #b { left: 600px; top: 2300px; width: 140px; height: 50px; background: blue; color: white; }
            </style></head><body style="height: 4000px">
            <button id="a" onclick="log('a', event)">Erstens</button>
            <button id="b" onclick="log('b', event)">Zweitens</button>
            <script>
              window.hits = [];
              function log(id, e) { hits.push(id + ':' + e.isTrusted + ':' + Math.round(e.clientX) + ',' + Math.round(e.clientY)); }
            </script></body></html>""";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void clicksWhereTheScreenshotShowsTheControl() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        byte[] html = PAGE.getBytes(StandardCharsets.UTF_8);
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            browser.navigate("http://localhost:" + site.getAddress().getPort() + "/");

            // 1. Mark centre in image pixels, converted with the legend's mapping, clicked by coordinates.
            PageScreenshot top = browser.capture(PageScreenshot.Mode.VIEWPORT, null, true);
            PageScreenshot.Mark a = mark(top, "Erstens");
            double[] imagePoint = {a.box().centerX() * top.scale(), a.box().centerY() * top.scale()};
            double[] point = top.toViewport(imagePoint[0], imagePoint[1]);
            browser.mouseClick(point[0], point[1], "left", null);
            assertEquals(List.of("a:true:345,220"), browser.evaluate("() => hits"), top.legend());

            // 2. After wheel scrolling, the viewport image and its marks follow the scroll offset.
            browser.scroll(0, 2000, null, null, null, null);
            PageScreenshot scrolled = browser.capture(PageScreenshot.Mode.VIEWPORT, null, true);
            assertTrue(((Number) browser.evaluate("() => scrollY")).doubleValue() > 0, scrolled.legend());
            PageScreenshot.Mark b = mark(scrolled, "Zweitens");
            double[] bPoint = scrolled.toViewport(b.box().centerX() * scrolled.scale(), b.box().centerY() * scrolled.scale());
            browser.mouseClick(bPoint[0], bPoint[1], "left", null);
            Object hits = browser.evaluate("() => hits");
            assertEquals(2, ((List<?>) hits).size(), scrolled.legend());
            assertTrue(((List<?>) hits).get(1).toString().startsWith("b:true:670,"), hits + "\n" + scrolled.legend());

            // 3. A point taken from a full-page image maps to the same viewport point at the current scroll.
            PageScreenshot full = browser.capture(PageScreenshot.Mode.FULL, null, false);
            double[] fromFull = full.toViewport((600 + 10) * full.scale(), (2300 + 10) * full.scale());
            assertEquals(bPoint[0] - 60, fromFull[0], 1.5);
            assertEquals(b.box().y() + 10, fromFull[1], 1.5);
        } finally {
            site.stop(0);
        }
    }

    private static PageScreenshot.Mark mark(PageScreenshot shot, String name) {
        return shot.marks().stream().filter(m -> m.label().contains(name)).findFirst()
                .orElseThrow(() -> new AssertionError(shot.legend()));
    }
}
