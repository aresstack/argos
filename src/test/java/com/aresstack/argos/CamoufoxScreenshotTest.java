package com.aresstack.argos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: viewport, full-page and element screenshots against a local page with known geometry. */
class CamoufoxScreenshotTest {
    @TempDir Path temporary;

    /** Fixed boxes: a red button at viewport (40, 50) 100x30 and a blue one far below the fold. */
    private static final String PAGE = """
            <html><head><title>Bilder</title><style>
              body { margin: 0; background: #fff; }
              button { position: absolute; border: 0; padding: 0; color: #fff; }
              #go { left: 40px; top: 50px; width: 100px; height: 30px; background: rgb(255, 0, 0); }
              #far { left: 200px; top: 2400px; width: 120px; height: 40px; background: rgb(0, 0, 255); }
            </style></head><body style="height: 3000px">
            <button id="go" onclick="document.getElementById('log').textContent = 'clicked ' + event.isTrusted">Suchen</button>
            <button id="far">Weit unten</button>
            <p id="log" style="position: absolute; top: 120px; left: 40px">idle</p>
            <script>
              window.traces = [];
              for (const type of ['__playwright_mark_target__', '__playwright_reset_targets__', 'scroll'])
                document.addEventListener(type, () => traces.push(type), true);
            </script></body></html>""";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void screenshotsShareCoordinatesWithTheMouseAndLeaveThePageAlone() throws Exception {
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
            String dom = "() => document.documentElement.outerHTML";
            Object before = browser.evaluate(dom);

            // Viewport with marks: the image shows the page at CSS scale, the legend gives viewport centres.
            PageScreenshot view = browser.capture(PageScreenshot.Mode.VIEWPORT, null, true);
            BufferedImage image = read(view.png());
            assertEquals(view.width(), image.getWidth());
            // Camoufox picks the window size from its fingerprint; wide windows are scaled to fit.
            double scale = view.scale();
            assertTrue(scale <= 1 && Math.max(image.getWidth(), image.getHeight()) <= PageScreenshot.MAX_SIDE, view.legend());
            assertTrue(isRed(image.getRGB((int) (46 * scale), (int) (56 * scale))), view.legend());
            assertArrayEquals(new double[] {90, 65}, view.toViewport(90 * scale, 65 * scale), 0.5);
            PageScreenshot.Mark go = view.marks().stream().filter(m -> m.label().contains("Suchen")).findFirst()
                    .orElseThrow(() -> new AssertionError(view.legend()));
            assertEquals(90, go.box().centerX(), 1);
            assertEquals(65, go.box().centerY(), 1);
            assertTrue(view.legend().contains("[" + go.number() + "] button \"Suchen\" center 90,65"), view.legend());
            // The mark's number is the control number the click tools use.
            browser.clickTarget(String.valueOf(go.number()));
            assertEquals("clicked true", browser.evaluate("() => document.getElementById('log').textContent"));

            // Full page: whole document, scaled to fit, still mapped back to the viewport.
            PageScreenshot full = browser.capture(PageScreenshot.Mode.FULL, null, false);
            assertEquals(PageScreenshot.MAX_SIDE, full.height(), full.legend());
            double[] farInViewport = full.toViewport(260 * full.scale(), 2420 * full.scale());
            assertArrayEquals(new double[] {260, 2420}, farInViewport, 1);
            assertTrue(isBlue(read(full.png()).getRGB((int) (206 * full.scale()), (int) (2406 * full.scale()))), full.legend());

            // Element below the fold: captured without scrolling; origin is its viewport box.
            PageScreenshot far = browser.capture(PageScreenshot.Mode.ELEMENT, "role=button name=\"Weit unten\"", false);
            BufferedImage element = read(far.png());
            assertEquals(120, element.getWidth(), 1);
            assertEquals(40, element.getHeight(), 1);
            assertTrue(isBlue(element.getRGB(6, 6)), far.legend());
            assertArrayEquals(new double[] {260, 2420}, far.toViewport(60, 20), 1);

            assertEquals(0, ((Number) browser.evaluate("() => scrollY")).intValue());
            assertEquals(List.of(), browser.evaluate("() => traces"));
            // No overlay or marker reached the page; only the click changed the log line.
            assertEquals(before.toString().replace(">idle<", ">clicked true<"), browser.evaluate(dom));
        } finally {
            site.stop(0);
        }
    }

    private static BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static boolean isRed(int rgb) {
        return ((rgb >> 16) & 0xff) > 200 && ((rgb >> 8) & 0xff) < 60 && (rgb & 0xff) < 60;
    }

    private static boolean isBlue(int rgb) {
        return ((rgb >> 16) & 0xff) < 60 && ((rgb >> 8) & 0xff) < 60 && (rgb & 0xff) > 200;
    }
}
