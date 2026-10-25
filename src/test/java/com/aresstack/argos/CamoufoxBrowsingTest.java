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

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void browsesWithTabsHistoryControlsAndExtraction() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            byte[] body = (exchange.getRequestURI().getPath().startsWith("/second") ? SECOND : FORM)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true))) {
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
            // Umlauts arrive correctly, but Playwright Core types non-US characters through its insertText
            // helper (untrusted InputEvent, playwright4j#36). Checked separately so marker events stay clean.
            browser.fillTarget("1", "Größe Übermaß");
            assertEquals("Größe Übermaß", browser.evaluate("() => document.getElementById('q').value"));
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
        } finally {
            site.stop(0);
        }
    }
}
