package com.aresstack.argos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in diagnostics, no acceptance test ({@code -PtestCamoufox=true -PdragDiagnostics=true}): plays the same HTML
 * drag with several input variants and prints the full event trace of each, so a failing {@link DragToolsBrowserTest}
 * shows where Gecko's drag session stops. Writes {@code build/drag-diagnostics.txt}. Asserts only that it could run.
 */
class DragDiagnosticsBrowserTest {
    @TempDir Path temporary;

    private static final String PAGE = """
            <html><head><title>Drag diagnostics</title><style>
              body { margin: 0; }
              #card { position: absolute; left: 40px; top: 60px; width: 120px; height: 50px; background: #fc6; }
              #done { position: absolute; left: 420px; top: 260px; width: 220px; height: 160px; background: #9cf; }
            </style></head><body>
            <div id="card" draggable="true">Karte 1</div>
            <div id="done">Erledigt</div>
            <script>
              window.trace = [];
              const t0 = performance.now();
              const log = (e, extra) => trace.push(Math.round(performance.now() - t0) + ' ' + e.type + '@'
                  + (e.currentTarget.id || 'window') + ' ' + e.clientX + ',' + e.clientY + ' trusted=' + e.isTrusted
                  + (extra ? ' ' + extra : ''));
              let moves = 0;
              addEventListener('mousemove', () => moves++);
              for (const type of ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'pointercancel'])
                addEventListener(type, e => log(e, 'moves=' + moves));
              const card = document.getElementById('card'), done = document.getElementById('done');
              card.addEventListener('dragstart', e => { log(e); e.dataTransfer.setData('text/plain', 'karte-1'); });
              card.addEventListener('drag', e => { if (!trace.some(l => l.includes(' drag@'))) log(e); });
              card.addEventListener('dragend', e => log(e, 'dropEffect=' + e.dataTransfer.dropEffect));
              let overs = 0;
              done.addEventListener('dragenter', e => { log(e); e.preventDefault(); });
              done.addEventListener('dragover', e => { overs++; if (overs === 1) log(e); e.preventDefault(); });
              done.addEventListener('dragleave', e => log(e, 'overs=' + overs));
              done.addEventListener('drop', e => { e.preventDefault();
                  log(e, 'data=' + e.dataTransfer.getData('text/plain') + ' overs=' + overs); });
              window.summary = () => 'dragover total=' + overs + ', mousemove total=' + moves;
            </script></body></html>""";

    @Test @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void printsTheEventTraceOfEachDragVariant() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox") && Boolean.getBoolean("argos.dragDiagnostics"),
                "Opt-in drag diagnostics");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.setExecutor(Executors.newCachedThreadPool());
        site.start();
        List<String> report = new ArrayList<>();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("drag-diag-profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            List<SyncToolSpecification> tools = new ArgosTools(browser).forProfile(ToolProfile.EXPERT);
            String url = "http://localhost:" + site.getAddress().getPort() + "/";
            // Card centre (100, 85), drop zone centre (530, 340).

            run(report, browser, tools, url, "A browser_drag (RealDrag, human path)", () -> ok(tools, "browser_drag",
                    Map.of("source", "text=\"Karte 1\"", "target", "text=\"Erledigt\"")));

            run(report, browser, tools, url, "B raw page.mouse: down, 20 straight steps, 5 moves on target, up", () ->
                    browser.onActivePage(page -> {
                        page.mouse().move(100, 85);
                        page.mouse().down();
                        page.mouse().move(110, 90);
                        page.mouse().move(530, 340, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(20));
                        for (int i = 0; i < 5; i++) {
                            page.waitForTimeout(60);
                            page.mouse().move(530 + (i % 2), 340);
                        }
                        page.mouse().up();
                        return null;
                    }));

            run(report, browser, tools, url, "C raw page.mouse with pauses: like B, 1 s dwell in 20 moves before up", () ->
                    browser.onActivePage(page -> {
                        page.mouse().move(100, 85);
                        page.waitForTimeout(150);
                        page.mouse().down();
                        page.waitForTimeout(150);
                        for (int i = 1; i <= 4; i++) {
                            page.mouse().move(100 + 3 * i, 85 + i);
                            page.waitForTimeout(40);
                        }
                        page.mouse().move(530, 340, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(30));
                        for (int i = 0; i < 20; i++) {
                            page.waitForTimeout(50);
                            page.mouse().move(530 + (i % 2), 340 + (i % 3));
                        }
                        page.waitForTimeout(200);
                        page.mouse().up();
                        return null;
                    }));

            // Reference only: Playwright's own helper (fires marker events, never used by Argos).
            run(report, browser, tools, url, "D reference page.dragAndDrop (upstream: known failing over Firefox BiDi)", () ->
                    browser.onActivePage(page -> {
                        page.dragAndDrop("#card", "#done");
                        return null;
                    }));
        } finally {
            site.stop(0);
            String text = String.join(System.lineSeparator(), report);
            System.out.println(text);
            Files.writeString(Path.of("build", "drag-diagnostics.txt"), text, StandardCharsets.UTF_8);
        }
        assertFalse(report.isEmpty());
    }

    private static void run(List<String> report, CamoufoxBrowser browser, List<SyncToolSpecification> tools, String url, String name,
                            Runnable drag) {
        report.add("=== " + name);
        try {
            assertFalse(call(tools, "browser_navigate", Map.of("url", url)).isError());
            long start = System.nanoTime();
            try {
                drag.run();
            } catch (RuntimeException failed) {
                report.add("drag threw: " + failed);
            }
            report.add("drag took " + (System.nanoTime() - start) / 1_000_000 + " ms");
            // Give a pending drop or dragend time to arrive before reading.
            browser.onActivePage(page -> { page.waitForTimeout(500); return null; });
        } catch (Throwable setup) {
            report.add("setup failed: " + setup);
        }
        for (String expression : List.of("() => window.trace.join('\\n')", "() => window.summary()",
                "() => document.getElementById('card').parentElement.id")) {
            try {
                report.add(ok(tools, "browser_evaluate", Map.of("expression", expression)));
            } catch (Throwable read) {
                report.add("read failed: " + read);
            }
        }
    }

    private static String ok(List<SyncToolSpecification> tools, String name, Map<String, Object> arguments) {
        CallToolResult result = call(tools, name, arguments);
        String text = ((TextContent) result.content().get(0)).text();
        assertFalse(result.isError(), name + ": " + text);
        return text;
    }

    private static void assertErrorContains(List<SyncToolSpecification> tools, String name,
                                            Map<String, Object> arguments, String expected) {
        CallToolResult result = call(tools, name, arguments);
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(result.isError(), name + ": " + text);
        assertTrue(text.contains(expected), text);
    }

    private static CallToolResult call(List<SyncToolSpecification> tools, String name, Map<String, Object> arguments) {
        return tools.stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow()
                .callHandler().apply(null, new CallToolRequest(name, arguments));
    }
}
