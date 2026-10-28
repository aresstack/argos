package com.aresstack.argos;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in ({@code -PtestHumanBrowser=<path to camoufox, firefox or chromium>}): single keys and shortcuts reach the
 * page as trusted keydown events with the right modifier flags and have their real editing effect, and a refused
 * combination presses nothing.
 */
class KeyboardShortcutBrowserTest {
    @TempDir Path temporary;

    private static final String PAGE = """
            <html><head><title>Keys</title></head><body>
            <form id="f" onsubmit="seen.submits++; return false"><input id="q"></form>
            <script>
              window.seen = {keys: [], untrusted: 0, traces: [], submits: 0};
              for (const type of ['__playwright_mark_target__', '__playwright_reset_targets__'])
                document.addEventListener(type, () => seen.traces.push(type), true);
              addEventListener('keydown', e => {
                if (!e.isTrusted) seen.untrusted++;
                seen.keys.push([e.key, e.ctrlKey, e.shiftKey, e.altKey, e.metaKey]);
              }, true);
            </script></body></html>""";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @SuppressWarnings("unchecked")
    void shortcutsArriveAsTrustedKeysAndEdit() {
        String executable = HumanBrowserExecutable.resolve();
        Assumptions.assumeFalse(executable.isBlank(), "Opt-in real browser test");
        Path profile = temporary.resolve("profile");
        try (Playwright playwright = Playwright.create()) {
            boolean chromium = Path.of(executable).getFileName().toString().toLowerCase().contains("chrom");
            BrowserType type = chromium ? playwright.chromium() : playwright.firefox();
            BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                    .setExecutablePath(Path.of(executable)).setHeadless(true).setViewportSize(1200, 700);
            if (!chromium) options.setChannel("moz-firefox");
            try (BrowserContext context = type.launchPersistentContext(profile, options)) {
                Page page = context.pages().get(0);
                RealInput input = new RealInput(new HumanInputSettings(
                        HumanInputSettings.Mode.NATURAL, 1, 0, 2026L, HumanInputSettings.UnicodePolicy.STRICT));
                input.probeUnicode(page);
                page.setContent(PAGE);

                input.replaceText(page, page.locator("#q"), "abc def");
                input.press(page, null, "Shift+ArrowLeft", 4);
                input.press(page, null, "Backspace", 1);
                assertEquals("abc", page.evaluate("() => document.getElementById('q').value"));

                input.press(page, page.locator("#q"), "ControlOrMeta+a", 1);
                input.press(page, null, "Delete", 1);
                assertEquals("", page.evaluate("() => document.getElementById('q').value"));

                input.press(page, null, "Escape", 1);
                input.press(page, page.locator("#q"), "Enter", 1);
                Map<String, Object> seen = (Map<String, Object>) page.evaluate("() => window.seen");
                assertEquals(1, ((Number) seen.get("submits")).intValue(), "Enter submits only when pressed");
                List<List<Object>> keys = (List<List<Object>>) seen.get("keys");
                assertTrue(keys.stream().anyMatch(k -> "ArrowLeft".equals(k.get(0)) && Boolean.TRUE.equals(k.get(2))),
                        "ArrowLeft with Shift: " + keys);
                assertTrue(keys.stream().anyMatch(k -> "a".equals(k.get(0))
                        && (Boolean.TRUE.equals(k.get(1)) || Boolean.TRUE.equals(k.get(4)))), "a with Ctrl/Cmd: " + keys);
                assertTrue(keys.stream().anyMatch(k -> "Escape".equals(k.get(0))), keys.toString());
                assertEquals(0, ((Number) seen.get("untrusted")).intValue(), "only trusted events");
                assertEquals(List.of(), seen.get("traces"), "no Playwright marker events");

                int before = keys.size();
                assertThrows(IllegalArgumentException.class, () -> input.press(page, null, "Control+w", 1));
                assertThrows(IllegalArgumentException.class, () -> input.press(page, page.locator("#q"), "Hyper+a", 1));
                seen = (Map<String, Object>) page.evaluate("() => window.seen");
                assertEquals(before, ((List<?>) seen.get("keys")).size(), "a refused combination presses nothing");
            }
        }
    }
}
