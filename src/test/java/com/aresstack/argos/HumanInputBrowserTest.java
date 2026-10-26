package com.aresstack.argos;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in ({@code -PtestHumanBrowser=<path to camoufox, firefox or chromium>}): what a page's own listeners see of
 * the human input. Every event must be trusted, the pointer must enter at the top edge and travel without jumps on
 * a curve, the page must scroll by wheel, capitals must carry Shift, and Playwright must not mark targets.
 */
class HumanInputBrowserTest {
    private static final String PAGE = """
            <html><head><title>Human</title></head><body style="margin:0">
            <input id="q" style="margin:40px">
            <div style="height:2600px"></div>
            <button id="far" style="width:160px;height:40px">Weit unten</button>
            <script>
              window.seen = {moves: [], keys: [], wheels: 0, untrusted: 0, traces: [], clicks: []};
              const note = e => { if (!e.isTrusted) seen.untrusted++; };
              for (const type of ['__playwright_mark_target__', '__playwright_reset_targets__'])
                document.addEventListener(type, () => seen.traces.push(type), true);
              addEventListener('mousemove', e => { note(e); seen.moves.push([e.clientX, e.clientY, e.timeStamp]); }, true);
              addEventListener('wheel', e => { note(e); seen.wheels++; }, true);
              addEventListener('keydown', e => { note(e); seen.keys.push([e.key, e.shiftKey, e.timeStamp]); }, true);
              addEventListener('click', e => { note(e); seen.clicks.push([e.target.id, e.clientX, e.clientY]); }, true);
            </script></body></html>""";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @SuppressWarnings("unchecked")
    void pageSeesOnlyTrustedHumanInput() {
        String executable = System.getProperty("argos.testHumanBrowser", "");
        Assumptions.assumeFalse(executable.isBlank(), "Opt-in real browser test");
        try (Playwright playwright = Playwright.create()) {
            boolean chromium = Path.of(executable).getFileName().toString().toLowerCase().contains("chrom");
            BrowserType type = chromium ? playwright.chromium() : playwright.firefox();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setExecutablePath(Path.of(executable)).setHeadless(true);
            if (!chromium) options.setChannel("moz-firefox");
            try (Browser browser = type.launch(options)) {
                Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1200, 700));
                RealInput input = new RealInput(new HumanInputSettings(
                        HumanInputSettings.Mode.NATURAL, 1, 0, 2026L, HumanInputSettings.UnicodePolicy.STRICT));
                input.probeUnicode(page);
                page.setContent(PAGE);

                input.click(page, page.locator("#far"));
                Map<String, Object> seen = (Map<String, Object>) page.evaluate("() => window.seen");
                List<List<Object>> clicks = (List<List<Object>>) seen.get("clicks");
                assertEquals("far", clicks.get(0).get(0), seen.toString());
                assertTrue(((Number) seen.get("wheels")).intValue() > 0, "scrolled by wheel");
                List<List<Number>> moves = (List<List<Number>>) seen.get("moves");
                assertTrue(moves.size() > 15, "many small moves: " + moves.size());
                assertTrue(moves.get(0).get(1).doubleValue() <= 2, "enters at the top edge: " + moves.get(0));
                double longest = 0;
                for (int i = 1; i < moves.size(); i++) {
                    longest = Math.max(longest, Math.hypot(moves.get(i).get(0).doubleValue() - moves.get(i - 1).get(0).doubleValue(),
                            moves.get(i).get(1).doubleValue() - moves.get(i - 1).get(1).doubleValue()));
                }
                assertTrue(longest < 150, "no jump of the pointer: " + longest);

                input.replaceText(page, page.locator("#q"), "Hallo Welt");
                assertEquals("Hallo Welt", page.evaluate("() => document.getElementById('q').value"));
                seen = (Map<String, Object>) page.evaluate("() => window.seen");
                List<List<Object>> keys = (List<List<Object>>) seen.get("keys");
                assertTrue(keys.stream().anyMatch(k -> "H".equals(k.get(0)) && Boolean.TRUE.equals(k.get(1))),
                        "capital H with Shift: " + keys);
                assertEquals(0, ((Number) seen.get("untrusted")).intValue(), "only trusted events");
                assertEquals(List.of(), seen.get("traces"), "no Playwright marker events");

                // Playwright Core 1.62 refuses umlauts as real keys (playwright4j#36): fail closed, before the field
                // is focused or cleared. Once Playwright4J sends them through performActions, they must arrive as
                // trusted keys with the right event.key.
                int keysBefore = keys.size();
                try {
                    input.replaceText(page, page.locator("#q"), "Grüße äöüÄÖÜß€");
                    seen = (Map<String, Object>) page.evaluate("() => window.seen");
                    assertEquals("Grüße äöüÄÖÜß€", page.evaluate("() => document.getElementById('q').value"));
                    assertTrue(((List<List<Object>>) seen.get("keys")).stream().anyMatch(k -> "ß".equals(k.get(0))));
                    assertEquals(0, ((Number) seen.get("untrusted")).intValue(), "umlauts as trusted keys");
                } catch (IllegalStateException refused) {
                    assertTrue(refused.getMessage().contains("playwright4j#36"), refused.getMessage());
                    assertEquals("Hallo Welt", page.evaluate("() => document.getElementById('q').value"),
                            "the field is untouched");
                    seen = (Map<String, Object>) page.evaluate("() => window.seen");
                    assertEquals(keysBefore, ((List<?>) seen.get("keys")).size(), "not a single key was pressed");
                }
            }
        }
    }
}
