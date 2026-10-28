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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in Camoufox tests for browser_drag, split by what Firefox supports (Angelo's decision, 2026-10-27):
 * <ul>
 *   <li>Pointer drag (acceptance): a card moved with pointer events onto a lane, and a mouse-driven slider, both
 *       with trusted events from start to release.</li>
 *   <li>HTML5 drag (known limit): Firefox does not finish native drag and drop started by automated input (Mozilla
 *       bug 1515879). The test checks that the drag starts as trusted input and that the tool result carries the
 *       warning; it does not require a native drop. No script-made drop or dragend anywhere.</li>
 * </ul>
 */
class DragToolsBrowserTest {
    @TempDir Path temporary;

    /** Sortable-style card and slider that drag with pointer and mouse events, as SortableJS in fallback mode. */
    private static final String POINTER_PAGE = """
            <html><head><title>Pointer drag</title><style>
              body { margin: 0; }
              #card { position: absolute; left: 40px; top: 60px; width: 120px; height: 50px; background: #fc6;
                      touch-action: none; user-select: none; }
              #lane { position: absolute; left: 420px; top: 260px; width: 220px; height: 160px; background: #9cf; }
              #track { position: absolute; left: 40px; top: 480px; width: 400px; height: 20px; background: #ddd; }
              #knob { position: absolute; left: 0; top: 0; width: 20px; height: 20px; background: #333; }
            </style></head><body>
            <div id="lane" role="region" aria-label="Erledigt">Erledigt</div>
            <div id="card">Karte 1</div>
            <div id="track"><div id="knob"></div></div>
            <script>
              window.events = [];
              const card = document.getElementById('card'), lane = document.getElementById('lane');
              // The card follows beside the pointer, not under it, so Argos still sees the drop target there.
              let offset = null;
              card.addEventListener('pointerdown', e => { events.push('pointerdown:' + e.isTrusted);
                  offset = true; card.setPointerCapture(e.pointerId); });
              card.addEventListener('pointermove', e => { if (!offset) return;
                  card.style.left = (e.clientX + 16) + 'px'; card.style.top = (e.clientY + 16) + 'px'; });
              card.addEventListener('pointerup', e => { if (!offset) return; offset = null;
                  const over = document.elementsFromPoint(e.clientX, e.clientY).includes(lane);
                  events.push('pointerup:' + e.isTrusted + ':' + (over ? 'lane' : 'outside'));
                  if (over) { lane.appendChild(card); card.style.position = 'static'; } });
              card.addEventListener('lostpointercapture', e => events.push('lostpointercapture:' + e.isTrusted));
              const knob = document.getElementById('knob');
              let grab = null;
              knob.addEventListener('mousedown', e => grab = e.clientX - knob.offsetLeft);
              addEventListener('mousemove', e => { if (grab !== null)
                  knob.style.left = Math.max(0, Math.min(380, e.clientX - grab)) + 'px'; });
              addEventListener('mouseup', e => { if (grab !== null) events.push('slider:' + knob.offsetLeft + ':' + e.isTrusted);
                  grab = null; });
            </script></body></html>""";

    /** A native HTML5 draggable card and drop zone. */
    private static final String HTML5_PAGE = """
            <html><head><title>HTML5 drag</title><style>
              body { margin: 0; }
              #card { position: absolute; left: 40px; top: 60px; width: 120px; height: 50px; background: #fc6; }
              #done { position: absolute; left: 420px; top: 260px; width: 220px; height: 160px; background: #9cf; }
            </style></head><body>
            <div id="card" draggable="true">Karte 1</div>
            <div id="done" role="region" aria-label="Erledigt">Erledigt</div>
            <script>
              window.events = [];
              const card = document.getElementById('card'), done = document.getElementById('done');
              card.addEventListener('dragstart', e => { events.push('dragstart:' + e.isTrusted);
                  e.dataTransfer.setData('text/plain', 'karte-1'); });
              card.addEventListener('dragend', e => events.push('dragend:' + e.isTrusted));
              done.addEventListener('dragover', e => e.preventDefault());
              done.addEventListener('drop', e => { e.preventDefault();
                  events.push('drop:' + e.isTrusted + ':' + e.dataTransfer.getData('text/plain'));
                  done.appendChild(card); });
            </script></body></html>""";

    /** Acceptance: pointer- and mouse-event drags complete with trusted input from press to release. */
    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pointerDragsCompleteWithTrustedEvents() throws Exception {
        withBrowser(POINTER_PAGE, "pointer-drag-profile", tools -> {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode dragged = mapper.readTree(ok(tools, "browser_drag",
                    Map.of("source", "text=\"Karte 1\"", "target", "role=region name=\"Erledigt\"")));
            assertEquals("dragged", dragged.get("action").asText());
            assertFalse(dragged.has("warning"), "no HTML5 source, no warning: " + dragged);
            String events = ok(tools, "browser_evaluate", Map.of("expression", "() => window.events.splice(0)"));
            assertTrue(events.contains("\"pointerdown:true\"") && events.contains("\"pointerup:true:lane\"")
                    && events.contains("\"lostpointercapture:true\""), events);
            assertFalse(events.contains(":false"), events);
            assertEquals("\"lane\"", ok(tools, "browser_evaluate",
                    Map.of("expression", "() => document.getElementById('card').parentElement.id")));

            // Script-made slider: plain trusted mousedown/mousemove/mouseup at coordinates.
            ok(tools, "browser_drag", Map.of("from_x", 50, "from_y", 490, "to_x", 250, "to_y", 492));
            String slider = ok(tools, "browser_evaluate", Map.of("expression", "() => window.events.splice(0)"));
            assertTrue(slider.matches("\\[\"slider:(19\\d|20\\d|21\\d):true\"\\]"), slider);

            assertErrorContains(tools, "browser_drag", Map.of("target", "text=\"Erledigt\""), "source");
            assertErrorContains(tools, "browser_drag", Map.of("source", "text=\"Karte 1\"", "to_x", 10), "to_x");
            assertErrorContains(tools, "browser_drag",
                    Map.of("source", "text=\"Karte 1\"", "target", "text=\"Gibt es nicht\""), "Nothing on the page");
        });
    }

    /**
     * Known limit (Mozilla bug 1515879): the drag starts as trusted input and the result warns. A native drop is not
     * required; should a future Firefox deliver one, it must be trusted and carry the data.
     */
    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void html5DragReportsTheKnownFirefoxLimit() throws Exception {
        withBrowser(HTML5_PAGE, "html5-drag-profile", tools -> {
            JsonNode dragged = new ObjectMapper().readTree(ok(tools, "browser_drag",
                    Map.of("source", "text=\"Karte 1\"", "target", "role=region name=\"Erledigt\"")));
            assertEquals("dragged", dragged.get("action").asText());
            assertTrue(dragged.path("warning").asText().contains("1515879"), dragged.toString());
            String events = ok(tools, "browser_evaluate", Map.of("expression", "() => window.events.splice(0)"));
            assertTrue(events.contains("\"dragstart:true\""), events);
            assertFalse(events.contains(":false"), events);
            if (events.contains("\"drop:")) assertTrue(events.contains("\"drop:true:karte-1\""), events);
        });
    }

    private interface BrowserCheck {
        void run(List<SyncToolSpecification> tools) throws Exception;
    }

    private void withBrowser(String page, String profile, BrowserCheck check) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            byte[] body = page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.setExecutor(Executors.newCachedThreadPool());
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve(profile), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            List<SyncToolSpecification> tools = new ArgosTools(browser).forProfile(ToolProfile.EXPERT);
            String base = "http://localhost:" + site.getAddress().getPort();
            assertFalse(call(tools, "browser_navigate", Map.of("url", base + "/")).isError());
            check.run(tools);
        } finally {
            site.stop(0);
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
