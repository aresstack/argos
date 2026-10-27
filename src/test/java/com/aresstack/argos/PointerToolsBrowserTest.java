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

/** Opt-in Camoufox test: pointer move, click, hover and wheel through the MCP tools on a local page. */
class PointerToolsBrowserTest {
    @TempDir Path temporary;

    /** A fixed pad that logs pointer events, a hover menu, and a scrolling list on a page wider and taller than the window. */
    private static final String PAGE = """
            <html><head><title>Pointer</title><style>
              body { margin: 0; width: 3000px; height: 4000px; }
              #pad { position: fixed; left: 0; top: 0; width: 400px; height: 300px; background: #eee; }
              #menu { position: fixed; left: 500px; top: 40px; width: 160px; }
              #sub { display: none; }
              #menu:hover #sub { display: block; }
              #box { position: fixed; left: 500px; top: 150px; width: 200px; height: 150px; overflow: auto; }
            </style></head><body>
            <div id="pad"></div>
            <nav id="menu"><button>Produkte</button><div id="sub">Untermenü</div></nav>
            <div id="box"><div style="height:2000px">Liste</div></div>
            <script>
              window.events = [];
              window.last = null;
              const pad = document.getElementById('pad');
              pad.addEventListener('mousemove', e => window.last = [e.clientX, e.clientY, e.isTrusted]);
              for (const type of ['mousedown', 'mouseup', 'click', 'contextmenu'])
                pad.addEventListener(type, e => {
                  events.push(type + ':' + e.button + ':' + e.clientX + ',' + e.clientY + ':' + e.isTrusted);
                  if (type === 'contextmenu') e.preventDefault();
                });
              const menu = document.getElementById('menu');
              menu.addEventListener('mouseenter', e => events.push('enter:' + e.isTrusted));
              menu.addEventListener('click', () => events.push('menuclick'));
            </script></body></html>""";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void movesClicksHoversAndScrollsWithRealMouseInput() throws Exception {
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
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("pointer-profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            List<SyncToolSpecification> tools = new ArgosTools(browser).forProfile(ToolProfile.EXPERT);
            ObjectMapper mapper = new ObjectMapper();
            String base = "http://localhost:" + site.getAddress().getPort();
            assertFalse(call(tools, "browser_navigate", Map.of("url", base + "/")).isError());

            JsonNode moved = mapper.readTree(ok(tools, "browser_mouse_move", Map.of("x", 123, "y", "77")));
            assertEquals(123, moved.get("x").asInt());
            assertEquals(77, moved.get("y").asInt());
            assertEquals("[123,77,true]", ok(tools, "browser_evaluate", Map.of("expression", "() => window.last")));
            assertEquals("[]", ok(tools, "browser_evaluate", Map.of("expression", "() => window.events")),
                    "moving presses nothing");

            ok(tools, "browser_mouse_click", Map.of("x", 200, "y", 150));
            assertEquals("[\"mousedown:0:200,150:true\",\"mouseup:0:200,150:true\",\"click:0:200,150:true\"]",
                    ok(tools, "browser_evaluate", Map.of("expression", "() => window.events.splice(0)")));
            JsonNode right = mapper.readTree(ok(tools, "browser_mouse_click",
                    Map.of("x", 50, "y", 60, "button", "right")));
            assertEquals("right", right.get("button").asText());
            String rightEvents = ok(tools, "browser_evaluate", Map.of("expression", "() => window.events.splice(0)"));
            assertTrue(rightEvents.contains("mousedown:2:50,60:true") && rightEvents.contains("contextmenu:2:50,60:true"),
                    rightEvents);
            assertFalse(rightEvents.contains("\"click:"), rightEvents);

            JsonNode hovered = mapper.readTree(ok(tools, "browser_hover",
                    Map.of("target", "role=button name=\"Produkte\"")));
            assertTrue(hovered.get("target").asText().contains("Produkte"), hovered.toString());
            assertEquals("\"block\"", ok(tools, "browser_evaluate", Map.of("expression",
                    "() => getComputedStyle(document.getElementById('sub')).display")), "CSS :hover applies");
            assertEquals("[\"enter:true\"]",
                    ok(tools, "browser_evaluate", Map.of("expression", "() => window.events.splice(0)")),
                    "hovering enters the menu but never clicks it");

            JsonNode down = mapper.readTree(ok(tools, "browser_scroll", Map.of("delta_y", 400, "x", 200, "y", 150)));
            assertTrue(down.get("pageScrollY").asDouble() > 0, down.toString());
            JsonNode sideways = mapper.readTree(ok(tools, "browser_scroll", Map.of("delta_x", 300)));
            assertTrue(sideways.get("pageScrollX").asDouble() > 0, sideways.toString());
            double pageY = sideways.get("pageScrollY").asDouble();
            JsonNode up = mapper.readTree(ok(tools, "browser_scroll", Map.of("delta_y", -200)));
            assertTrue(up.get("pageScrollY").asDouble() < pageY, up.toString());

            JsonNode inBox = mapper.readTree(ok(tools, "browser_scroll", Map.of("delta_y", 300, "x", 600, "y", 225)));
            assertEquals(up.get("pageScrollY").asDouble(), inBox.get("pageScrollY").asDouble(),
                    "the list under the pointer scrolls, not the page");
            String boxTop = ok(tools, "browser_evaluate",
                    Map.of("expression", "() => document.getElementById('box').scrollTop"));
            assertTrue(Double.parseDouble(boxTop) > 0, boxTop);

            assertErrorContains(tools, "browser_mouse_move", Map.of("x", 99_999, "y", 10), "outside the viewport");
            assertErrorContains(tools, "browser_mouse_click", Map.of("x", -1, "y", 10), "outside the viewport");
            assertErrorContains(tools, "browser_mouse_move", Map.of("x", 10, "y", 10, "tab", "t9"), "t9");
            assertErrorContains(tools, "browser_hover", Map.of("target", "text=\"Gibt es nicht\""), "Nothing on the page");
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
