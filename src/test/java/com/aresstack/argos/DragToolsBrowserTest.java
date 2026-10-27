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
 * Opt-in Camoufox test: browser_drag moves a card into a drop zone with real mouse input, so the browser itself
 * fires trusted HTML drag and drop events, and drags a script-made slider handle by coordinates.
 */
class DragToolsBrowserTest {
    @TempDir Path temporary;

    private static final String PAGE = """
            <html><head><title>Drag</title><style>
              body { margin: 0; }
              #card { position: absolute; left: 40px; top: 60px; width: 120px; height: 50px; background: #fc6; }
              #done { position: absolute; left: 420px; top: 260px; width: 220px; height: 160px; background: #9cf; }
              #track { position: absolute; left: 40px; top: 480px; width: 400px; height: 20px; background: #ddd; }
              #knob { position: absolute; left: 0; top: 0; width: 20px; height: 20px; background: #333; }
            </style></head><body>
            <div id="card" draggable="true">Karte 1</div>
            <div id="done" role="region" aria-label="Erledigt">Erledigt</div>
            <div id="track"><div id="knob"></div></div>
            <script>
              window.events = [];
              const card = document.getElementById('card'), done = document.getElementById('done');
              card.addEventListener('dragstart', e => { events.push('dragstart:' + e.isTrusted);
                  e.dataTransfer.setData('text/plain', 'karte-1'); });
              card.addEventListener('dragend', e => events.push('dragend:' + e.isTrusted));
              done.addEventListener('dragenter', e => { events.push('dragenter:' + e.isTrusted); e.preventDefault(); });
              done.addEventListener('dragover', e => { if (!events.includes('dragover:' + e.isTrusted))
                  events.push('dragover:' + e.isTrusted); e.preventDefault(); });
              done.addEventListener('drop', e => { e.preventDefault();
                  events.push('drop:' + e.isTrusted + ':' + e.dataTransfer.getData('text/plain'));
                  done.appendChild(card); });
              const knob = document.getElementById('knob');
              let grab = null;
              knob.addEventListener('mousedown', e => grab = e.clientX - knob.offsetLeft);
              addEventListener('mousemove', e => { if (grab !== null)
                  knob.style.left = Math.max(0, Math.min(380, e.clientX - grab)) + 'px'; });
              addEventListener('mouseup', e => { if (grab !== null) events.push('slider:' + knob.offsetLeft + ':' + e.isTrusted);
                  grab = null; });
            </script></body></html>""";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void dragsWithTheBrowsersOwnDragAndDropEvents() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
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
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("drag-profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            List<SyncToolSpecification> tools = new ArgosTools(browser).forProfile(ToolProfile.EXPERT);
            ObjectMapper mapper = new ObjectMapper();
            String base = "http://localhost:" + site.getAddress().getPort();
            assertFalse(call(tools, "browser_navigate", Map.of("url", base + "/")).isError());

            JsonNode dragged = mapper.readTree(ok(tools, "browser_drag",
                    Map.of("source", "text=\"Karte 1\"", "target", "role=region name=\"Erledigt\"")));
            assertEquals("dragged", dragged.get("action").asText());
            String events = ok(tools, "browser_evaluate", Map.of("expression", "() => window.events.splice(0)"));
            assertTrue(events.contains("\"dragstart:true\"") && events.contains("\"dragenter:true\"")
                    && events.contains("\"dragover:true\"") && events.contains("\"drop:true:karte-1\"")
                    && events.contains("\"dragend:true\""), events);
            assertFalse(events.contains(":false"), events);
            assertEquals("\"done\"", ok(tools, "browser_evaluate",
                    Map.of("expression", "() => document.getElementById('card').parentElement.id")));

            // Script-made slider: plain trusted mousedown/mousemove/mouseup at coordinates.
            ok(tools, "browser_drag", Map.of("from_x", 50, "from_y", 490, "to_x", 250, "to_y", 492));
            String slider = ok(tools, "browser_evaluate", Map.of("expression", "() => window.events.splice(0)"));
            assertTrue(slider.matches("\\[\"slider:(19\\d|20\\d|21\\d):true\"\\]"), slider);

            assertErrorContains(tools, "browser_drag", Map.of("target", "text=\"Erledigt\""), "source");
            assertErrorContains(tools, "browser_drag", Map.of("source", "text=\"Karte 1\"", "to_x", 10), "to_x");
            assertErrorContains(tools, "browser_drag",
                    Map.of("source", "text=\"Karte 1\"", "target", "text=\"Gibt es nicht\""), "Nothing on the page");
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
