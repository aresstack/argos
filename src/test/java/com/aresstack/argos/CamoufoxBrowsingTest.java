package com.aresstack.argos;

import com.aresstack.argos.web.UrlPolicy;
import com.aresstack.argos.web.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: tabs, history, semantic controls with real input, and extraction against a local test site. */
class CamoufoxBrowsingTest {
    @TempDir Path temporary;

    private static final String FORM = """
            <html lang="de"><head><title>Formular</title>
            <meta name="description" content="Testformular">
            <meta name="citation_title" content="Ein Aufsatz">
            <script type="application/ld+json">{"@type":"ScholarlyArticle","name":"Ein Aufsatz"}</script>
            </head><body><main>
            <h1>Suche</h1>
            <label for="q">Suchbegriff</label><input id="q">
            <input type="password" aria-label="Passwort" value="geheim">
            <button id="go">Suchen</button>
            <label for="ort">Ort</label><input id="ort" placeholder="Suchen">
            <textarea aria-label="Notiz" id="note"></textarea>
            <div id="slot"><button id="again">Nochmal</button></div>
            <div style="height:3000px"><button id="tall" style="height:2500px;width:200px"
              onclick="this.textContent='tall clicked'">Hoch</button></div>
            <p id="log">idle</p>
            <a href="/second">Zweite Seite</a>
            <a href="/second" target="_blank">Neuer Tab</a>
            <table><tr><th>Jahr</th><th>Zitate</th></tr><tr><td>2025</td><td>12</td></tr></table>
            <ul><li>eins</li><li>zwei</li></ul>
            </main>
            <script>
              // What a bot detector could listen for: Playwright's target-marking events and scripted scrolling.
              window.traces = [];
              for (const type of ['__playwright_mark_target__', '__playwright_reset_targets__'])
                document.addEventListener(type, () => traces.push(type), true);
              const log = document.getElementById('log');
              // Re-render: the button is replaced by an equal new element after each click.
              document.getElementById('slot').addEventListener('click', e => {
                if (e.target.id !== 'again') return;
                const fresh = document.createElement('button');
                fresh.id = 'again'; fresh.textContent = 'Nochmal';
                fresh.dataset.count = String(Number(e.target.dataset.count || 0) + 1);
                document.getElementById('slot').replaceChildren(fresh);
              });
              document.getElementById('go').addEventListener('click', e =>
                log.textContent = 'clicked trusted=' + e.isTrusted + ' value=' + document.getElementById('q').value);
              document.getElementById('q').addEventListener('keydown', e => { if (!e.isTrusted) log.textContent = 'untrusted key'; });
            </script></body></html>""";
    private static final String SECOND = """
            <html><head><title>Zweite</title></head><body><h1>Zweite Seite</h1></body></html>""";
    private static final String THIRD = """
            <html><head><title>Dritte</title></head><body><h1>Dritte Seite</h1></body></html>""";
    /** A server error page, delivered with status 500 and rendered like any other document. */
    private static final String BROKEN = """
            <html><head><title>500 Internal Server Error</title></head><body><h1>Internal Server Error</h1>
            <p>The archive is unavailable.</p></body></html>""";
    /** A single-page app: the document says it is loading until its own request answers. */
    private static final String LOADING = """
            <html><head><title>Daten</title></head><body><p id="d">Wird geladen …</p>
            <script>fetch('/data').then(r => r.text()).then(t => { document.getElementById('d').textContent = t; });</script>
            </body></html>""";

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void browsesWithTabsHistoryControlsAndExtraction() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/data")) {
                try { Thread.sleep(1_200); } catch (InterruptedException ignored) { }
                byte[] data = "Messwerte geladen: 42".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, data.length);
                try (var out = exchange.getResponseBody()) { out.write(data); }
                return;
            }
            byte[] body = (path.startsWith("/second") ? SECOND : path.startsWith("/loading") ? LOADING
                    : path.startsWith("/broken") ? BROKEN : FORM).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(path.startsWith("/broken") ? 500 : 200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            String base = "http://localhost:" + site.getAddress().getPort();
            browser.navigate(base + "/");
            String globals = "() => Object.keys(window).filter(k => /playwright|__pw|aria|argos/i.test(k))";
            // Playwright Core's BiDi layer itself installs window.__pw_bidiInsertText on every document
            // (BidiBrowserContext.initialize -> extendInjectedScript). Argos must not add anything beyond it.
            Object baseline = browser.evaluate(globals);

            PageControls controls = browser.controls();
            List<String> lines = controls.all().stream().map(PageControls.Control::line).toList();
            assertTrue(lines.contains("[1] textbox \"Suchbegriff\""), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.contains("Passwort") && l.endsWith("= (hidden)")), lines.toString());
            assertFalse(lines.toString().contains("geheim"));

            browser.fillTarget("1", "Wien");
            browser.clickTarget("Suchen");
            assertEquals("clicked trusted=true value=Wien",
                    browser.evaluate("() => document.getElementById('log').textContent"));
            browser.fillTarget("label=\"Suchbegriff\"", "Graz");
            browser.clickTarget("role=button name=\"Suchen\"");
            assertEquals("clicked trusted=true value=Graz",
                    browser.evaluate("() => document.getElementById('log').textContent"));
            // placeholder= means the placeholder engine, although a button has the same name.
            browser.fillTarget("placeholder=\"Suchen\"", "Linz");
            assertEquals("Linz", browser.evaluate("() => document.getElementById('ort').value"));
            // An element taller than the viewport is still hit by the verified random point.
            browser.clickTarget("Hoch");
            assertEquals("tall clicked", browser.evaluate("() => document.getElementById('tall').textContent"));
            // Password field: typed for real, never echoed by the control list.
            browser.fillTarget("Passwort", "neu-Geheim42");
            assertEquals("neu-Geheim42", browser.evaluate("() => document.querySelector('[type=password]').value"));
            assertFalse(browser.controls().all().toString().contains("neu-Geheim42"));
            // Longer input with a newline in a textarea.
            String longText = "Lorem ipsum dolor sit amet, ".repeat(8) + "\nzweite Zeile";
            browser.fillTarget("Notiz", longText);
            assertEquals(longText, browser.evaluate("() => document.getElementById('note').value"));
            // Re-render between listing and clicking: the stale ref is looked up again by role and name.
            int again = browser.controls().all().stream().filter(c -> c.name().equals("Nochmal")).findFirst()
                    .orElseThrow().number();
            browser.clickTarget(String.valueOf(again));
            browser.clickTarget(String.valueOf(again));
            assertEquals("2", browser.evaluate("() => document.getElementById('again').dataset.count"));
            browser.extract("overview", null);
            browser.extract("metadata", null);
            browser.extract("links", "main");
            assertEquals(List.of(), browser.evaluate("() => window.traces"),
                    "controls, real input and extraction must not dispatch Playwright marker events");
            // Umlauts: strict by default, real keys or a refusal before the first key; see UnicodeInputCamoufoxTest.
            // Nothing of the automation leaks into the page's own realm.
            assertEquals(baseline, browser.evaluate(globals));

            Map<String, Object> overview = browser.extract("overview", null);
            assertEquals("de", overview.get("lang"));
            assertEquals("Testformular", overview.get("description"));
            assertEquals(List.of("ScholarlyArticle"), overview.get("jsonLdTypes"));
            @SuppressWarnings("unchecked")
            Map<String, Object> table = ((List<Map<String, Object>>) browser.extract("tables", null).get("tables")).get(0);
            assertEquals(List.of("Jahr", "Zitate"), table.get("headers"));
            assertEquals(List.of(List.of("2025", "12")), table.get("rows"));
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) browser.extract("metadata", null).get("meta");
            assertEquals("Ein Aufsatz", meta.get("citation_title"));
            assertTrue(browser.extract("links", "main").get("links").toString().contains(base + "/second"));

            CamoufoxBrowser.Interaction popup = browser.clickTarget("Neuer Tab");
            assertEquals("t2", popup.openedTab());
            assertEquals(2, browser.tabs().size());
            assertTrue(popup.url().endsWith("/second"), popup.url());
            browser.selectTab("t1");
            assertEquals("t1", browser.status().get("tab"));
            assertEquals("Formular", browser.tabs().get(0).get("title"));
            browser.closeTab("t2");
            assertEquals(1, browser.tabs().size());
            assertThrows(IllegalStateException.class, () -> browser.closeTab("t1"));

            browser.clickTarget("Zweite Seite");
            assertTrue(browser.status().get("url").toString().endsWith("/second"));
            assertTrue(browser.historyBack().get("url").toString().endsWith("/"));
            assertTrue(browser.forward().get("url").toString().endsWith("/second"));
            assertEquals("Zweite", browser.reload().get("title"));
            Map<String, Object> newTab = browser.newTab(base + "/");
            assertEquals("t3", newTab.get("tab"));
            assertEquals("Formular", newTab.get("title"));
            // web_* share the tab with browser_*: web_read adopts the page another tool opened (expert profile).
            WebSession web = new WebSession(browser, UrlPolicy.from(TestNetworks.LOOPBACK), null);
            String adopted = web.read(1);
            assertTrue(adopted.startsWith("URL: " + base + "/\nTITLE: Formular\nLINKS: "), adopted);
            // A page that says it is loading is read again once its own request answered (#13, dynamic-page).
            String loaded = web.open(base + "/loading");
            assertTrue(loaded.contains("Messwerte geladen: 42"), loaded);
            assertFalse(loaded.contains("Wird geladen"), loaded);
            // An error page names its status from the navigation response (Phase 7, broken-link); the
            // delivered pages above carried no error line.
            assertFalse(loaded.contains("HTTP error:"), loaded);
            String broken = web.open(base + "/broken");
            assertTrue(broken.contains("TITLE: 500 Internal Server Error\nHTTP error: 500 Internal Server Error \u2014"), broken);
            assertTrue(broken.contains("The archive is unavailable."), broken);
            assertTrue(web.read(1).contains("\nHTTP error: 500 Internal Server Error \u2014"), "web_read keeps the status");
            // Playwright strips the fragment from the response URL; the shown address keeps it.
            String fragment = web.open(base + "/broken#detail");
            assertTrue(fragment.contains("\nHTTP error: 500 Internal Server Error \u2014"), fragment);
            assertTrue(fragment.startsWith("URL: " + base + "/broken#detail\n"), fragment);
        } finally {
            site.stop(0);
        }
    }

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pageTargetedMcpToolsKeepTabsIsolated() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String html = path.startsWith("/second") ? SECOND : path.startsWith("/third") ? THIRD : FORM;
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        site.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        site.start();

        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("page-target-profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            List<SyncToolSpecification> tools = new ArgosTools(browser).forProfile(ToolProfile.EXPERT);
            ObjectMapper mapper = new ObjectMapper();
            String base = "http://localhost:" + site.getAddress().getPort();

            assertFalse(callTool(tools, "browser_navigate", Map.of("url", base + "/")).isError());
            assertFalse(callTool(tools, "browser_tab_new", Map.of("url", base + "/second")).isError());

            JsonNode tabs = mapper.readTree(toolText(callTool(tools, "browser_tabs", Map.of())));
            assertEquals(2, tabs.size());
            assertTrue(tabs.toString().contains("\"t1\""));
            assertTrue(tabs.toString().contains("\"t2\""));
            assertEquals("t2", browser.status().get("tab"), "Opening a tab makes it current");

            String firstSnapshot = toolText(callTool(tools, "browser_snapshot", Map.of("tab", "t1")));
            String secondSnapshot = toolText(callTool(tools, "browser_snapshot", Map.of("tab", "t2")));
            assertTrue(firstSnapshot.contains("Formular"), firstSnapshot);
            assertTrue(secondSnapshot.contains("Zweite Seite"), secondSnapshot);

            String firstInspection = toolText(callTool(tools, "browser_inspect",
                    Map.of("selector", "h1", "tab", "t1")));
            String secondInspection = toolText(callTool(tools, "browser_inspect",
                    Map.of("selector", "h1", "tab", "t2")));
            assertTrue(firstInspection.contains("Suche"), firstInspection);
            assertTrue(secondInspection.contains("Zweite Seite"), secondInspection);

            assertFalse(callTool(tools, "browser_fill",
                    Map.of("selector", "#q", "text", "per-tab", "tab", "t1")).isError());
            assertFalse(callTool(tools, "browser_click", Map.of("selector", "#go", "tab", "t1")).isError());
            assertFalse(callTool(tools, "browser_fill_target",
                    Map.of("target", "label=\"Suchbegriff\"", "text", "semantic", "tab", "t1")).isError());
            assertFalse(callTool(tools, "browser_click_target",
                    Map.of("target", "role=button name=\"Suchen\"", "tab", "t1")).isError());
            String firstLog = toolText(callTool(tools, "browser_evaluate",
                    Map.of("expression", "() => document.getElementById('log').textContent", "tab", "t1")));
            assertTrue(firstLog.contains("clicked trusted=true value=semantic"), firstLog);
            String secondTitle = toolText(callTool(tools, "browser_evaluate",
                    Map.of("expression", "() => document.title", "tab", "t2")));
            assertTrue(secondTitle.contains("Zweite"), secondTitle);
            assertEquals("t2", browser.status().get("tab"), "Page-targeted calls must not switch the current tab");

            JsonNode firstNetwork = mapper.readTree(toolText(callTool(tools, "browser_network", Map.of("tab", "t1"))));
            JsonNode secondNetwork = mapper.readTree(toolText(callTool(tools, "browser_network", Map.of("tab", "t2"))));
            assertTrue(firstNetwork.toString().contains(base + "/"), firstNetwork.toString());
            assertFalse(firstNetwork.toString().contains("/second"), firstNetwork.toString());
            assertTrue(secondNetwork.toString().contains("/second"), secondNetwork.toString());
            String secondRequestId = secondNetwork.get(0).get("id").asText();
            CallToolResult crossTabDetail = callTool(tools, "browser_network_detail",
                    Map.of("id", secondRequestId, "body", false, "tab", "t1"));
            assertTrue(crossTabDetail.isError(), toolText(crossTabDetail));

            assertFalse(callTool(tools, "browser_navigate",
                    Map.of("url", base + "/third", "tab", "t1")).isError());
            String thirdSnapshot = toolText(callTool(tools, "browser_snapshot", Map.of("tab", "t1")));
            secondSnapshot = toolText(callTool(tools, "browser_snapshot", Map.of("tab", "t2")));
            assertTrue(thirdSnapshot.contains("Dritte Seite"), thirdSnapshot);
            assertTrue(secondSnapshot.contains("Zweite Seite"), secondSnapshot);

            CallToolResult firstScreenshot = callTool(tools, "browser_screenshot", Map.of("tab", "t1"));
            CallToolResult secondScreenshot = callTool(tools, "browser_screenshot", Map.of("tab", "t2"));
            assertFalse(firstScreenshot.isError());
            assertFalse(secondScreenshot.isError());
            ImageContent firstImage = assertInstanceOf(ImageContent.class, firstScreenshot.content().get(0));
            ImageContent secondImage = assertInstanceOf(ImageContent.class, secondScreenshot.content().get(0));
            assertNotEquals(firstImage.data(), secondImage.data(), "Screenshots must come from the requested tab");

            assertFalse(callTool(tools, "browser_tab_close", Map.of("tab", "t1")).isError());
            String remainingSnapshot = toolText(callTool(tools, "browser_snapshot", Map.of("tab", "t2")));
            assertTrue(remainingSnapshot.contains("Zweite Seite"), remainingSnapshot);
            assertTrue(callTool(tools, "browser_snapshot", Map.of("tab", "t1")).isError());
            assertTrue(callTool(tools, "browser_snapshot", Map.of("tab", "t9")).isError());
            assertEquals("t2", browser.status().get("tab"));
        } finally {
            site.stop(0);
        }
    }

    private static CallToolResult callTool(List<SyncToolSpecification> tools, String name,
                                           Map<String, Object> arguments) {
        SyncToolSpecification specification = tools.stream()
                .filter(candidate -> candidate.tool().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown test tool: " + name));
        return specification.callHandler().apply(null, new CallToolRequest(name, arguments));
    }

    private static String toolText(CallToolResult result) {
        if (result.content().isEmpty()) return "";
        if (result.content().get(0) instanceof TextContent text) return text.text();
        return result.content().get(0).toString();
    }
}
