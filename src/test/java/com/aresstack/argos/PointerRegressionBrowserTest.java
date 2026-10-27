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

/**
 * Opt-in Camoufox regressions from the acceptance run of 2026-10-27: mouse input accepts the whole viewport the
 * screenshot reports (#38), hover scrolls to an element whose centre lies on a fractional pixel (#37), and
 * click_target reaches a link at the very bottom of a page that cannot scroll it any higher (#38).
 */
class PointerRegressionBrowserTest {
    @TempDir Path temporary;

    private static final String PAGE = """
            <html><head><title>Regression</title><style>
              body { margin: 0; height: 5000px; position: relative; }
              #far { position: absolute; left: 300.5px; top: 2600.5px; width: 101px; height: 41px; }
              #last { position: absolute; left: 40px; bottom: 6px; }
              #pad { position: fixed; inset: 0; z-index: -1; }
            </style></head><body>
            <div id="pad"></div>
            <button id="far">Weit unten</button>
            <a id="last" href="#ende">Ganz unten</a>
            <script>
              window.lastMove = '';
              document.addEventListener('mousemove', e => { lastMove = e.clientX + ',' + e.clientY; });
            </script></body></html>""";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pointerAndScrollingMatchTheRealViewport() throws Exception {
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

            // 1. The lower right pixel of the viewport the screenshot reports is a valid mouse target.
            List<?> size = (List<?>) browser.evaluate("() => [innerWidth, innerHeight]");
            int width = ((Number) size.get(0)).intValue(), height = ((Number) size.get(1)).intValue();
            String legend = browser.capture(PageScreenshot.Mode.VIEWPORT, null, false).legend();
            assertTrue(legend.contains("Viewport: " + width + "x" + height), legend);
            browser.mouseMove(width - 1, height - 1, null);
            assertEquals((width - 1) + "," + (height - 1), browser.evaluate("() => lastMove"), legend);

            // 2. Hover scrolls a far element with a fractional centre into view and ends on top of it.
            browser.hover("role=button name=\"Weit unten\"", null);
            assertEquals(Boolean.TRUE, browser.evaluate(
                    "() => scrollY > 0 && document.getElementById('far').matches(':hover')"));

            // 3. A link the page cannot scroll higher than the bottom edge is still clicked.
            browser.clickTarget("role=link name=\"Ganz unten\"");
            assertEquals("#ende", browser.evaluate("() => location.hash"));
        } finally {
            site.stop(0);
        }
    }
}
