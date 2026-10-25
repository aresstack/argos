package com.aresstack.argos;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in ({@code -PtestCamoufox=true}): umlauts through the real tool path in Camoufox, as the page's own listeners
 * see them. A successful {@code keyboard.type("Grüße")} proves nothing here: Playwright Core types non-US characters
 * through a page script, and Argos' strict default must either send real, trusted key events or refuse before the
 * first key.
 *
 * <p>Run it against a Playwright4J fix with {@code -Pplaywright4jSource=<checkout of the PR head>}: that sets
 * {@code argos.expectUnicodeKeys}, and a refusal then fails the test instead of passing as fail-closed.
 */
class UnicodeInputCamoufoxTest {
    @TempDir Path temporary;

    private static final String PAGE = """
            <html><head><title>Unicode</title></head><body>
            <form action="/submitted"><label for="q">Suchbegriff</label><input id="q" name="q" value="alt">
            <button>Suchen</button></form>
            <label for="note">Notiz</label><textarea id="note"></textarea>
            <script>
              window.seen = {events: [], untrusted: 0, clicks: 0};
              for (const type of ['keydown', 'keyup', 'beforeinput', 'input'])
                addEventListener(type, e => {
                  if (!e.isTrusted) seen.untrusted++;
                  seen.events.push([type, type.endsWith('input') ? e.data : e.key, e.isTrusted, e.target.id]);
                }, true);
              addEventListener('mousedown', () => seen.clicks++, true);
            </script></body></html>""";

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    @SuppressWarnings("unchecked")
    void webFillTypesUmlautsAsTrustedKeysOrRefusesBeforeTheFirstKey() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        boolean expectKeys = Boolean.getBoolean("argos.expectUnicodeKeys");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        AtomicInteger submitted = new AtomicInteger();
        byte[] html = PAGE.getBytes(StandardCharsets.UTF_8);
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/submitted")) submitted.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true))) {
            browser.navigate("http://localhost:" + site.getAddress().getPort() + "/");
            RealInput.UnicodeKeys probed = browser.unicodeInput();
            System.out.println("Unicode input probed at browser start: " + probed + " (expected real keys: "
                    + expectKeys + ")");
            assertFalse(probed == RealInput.UnicodeKeys.UNKNOWN, "probeUnicode ran before the first website");
            boolean realKeys = probed == RealInput.UnicodeKeys.KEY_NAMES || probed == RealInput.UnicodeKeys.TEXT_AS_KEYS;
            if (expectKeys) {
                assertTrue(realKeys, "the Playwright4J under test must type umlauts as real keys, probe said " + probed);
            }

            CallToolResult fill = webFill(browser, "Suchbegriff", "Grüße");
            Map<String, Object> seen = (Map<String, Object>) browser.evaluate("() => window.seen");
            List<List<Object>> events = (List<List<Object>>) seen.get("events");
            if (realKeys) {
                assertFalse(fill.isError(), text(fill));
                assertEquals("Grüße", browser.evaluate("() => document.getElementById('q').value"));
                assertEquals(0, ((Number) seen.get("untrusted")).intValue(), "only trusted events: " + events);
                for (String character : List.of("ü", "ß")) {
                    for (String type : List.of("keydown", "keyup", "input")) {
                        assertTrue(events.stream().anyMatch(e -> type.equals(e.get(0)) && character.equals(e.get(1))
                                        && Boolean.TRUE.equals(e.get(2)) && "q".equals(e.get(3))),
                                "trusted " + type + " for " + character + ": " + events);
                    }
                }
                if (probed == RealInput.UnicodeKeys.TEXT_AS_KEYS) {
                    // playwright4j#59 drops Playwright's main-world text helper along with its use.
                    assertEquals("undefined", browser.evaluate("() => typeof window.__pw_bidiInsertText"));
                }
            } else {
                assertTrue(fill.isError(), text(fill));
                assertTrue(text(fill).contains("Nothing was typed"), text(fill));
                assertEquals("alt", browser.evaluate("() => document.getElementById('q').value"), "field untouched");
                assertEquals(List.of(), events, "not a single key before the refusal");
                assertEquals(0, ((Number) seen.get("clicks")).intValue(), "refused before the field was focused");
            }

            // A line break inside the text would press Enter in a one-line field and submit the form halfway.
            browser.evaluate("() => { window.seen = {events: [], untrusted: 0, clicks: 0}; }");
            String before = String.valueOf(browser.evaluate("() => document.getElementById('q').value"));
            CallToolResult multiLine = webFill(browser, "Suchbegriff", "x\ny");
            assertTrue(multiLine.isError(), text(multiLine));
            assertTrue(text(multiLine).contains("single-line"), text(multiLine));
            assertEquals(0, submitted.get(), "no form submission");
            assertEquals(before, browser.evaluate("() => document.getElementById('q').value"));
            assertEquals(List.of(), ((Map<String, Object>) browser.evaluate("() => window.seen")).get("events"));
            // In a text area the same text is two lines.
            CallToolResult note = webFill(browser, "Notiz", "x\ny");
            assertFalse(note.isError(), text(note));
            assertEquals("x\ny", browser.evaluate("() => document.getElementById('note').value"));
            assertEquals(0, submitted.get());
        } finally {
            site.stop(0);
        }
    }

    private static CallToolResult webFill(CamoufoxBrowser browser, String target, String text) {
        SyncToolSpecification tool = new ArgosBrowsingTools(browser).compact().stream()
                .filter(spec -> spec.tool().name().equals("web_fill")).findFirst().orElseThrow();
        return tool.callHandler().apply(null, new CallToolRequest("web_fill", Map.of("target", target, "text", text)));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
