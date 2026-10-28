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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in ({@code -PtestHumanBrowser=<path to camoufox, firefox or chromium>}): filling never selects the page text.
 * Date and time fields are filled segment by segment without select-all, and a field whose click is swallowed by an overlay gets no
 * select-all; a normal field after them still fills.
 */
class FieldSelectionBrowserTest {
    @TempDir Path temporary;

    private static final String PAGE = """
            <html><head><title>Fields</title></head><body>
            <p>Lorem ipsum dolor sit amet, a paragraph that must never get selected.</p>
            <form onsubmit="return false">
              <input id="name"><br>
              <input id="time" type="time"><br>
              <input id="date" type="date"><br>
              <div style="position:relative">
                <input id="covered">
                <div id="cover" tabindex="0" style="position:absolute;inset:0;background:transparent"></div>
              </div>
            </form>
            <p>More text below the form.</p>
            </body></html>""";

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void fillingNeverSelectsThePage() {
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

                input.replaceText(page, page.locator("#time"), "18:30");
                assertEquals("18:30", page.evaluate("() => document.getElementById('time').value"));
                assertEquals("", selection(page), "time field: no page selection");
                input.replaceText(page, page.locator("#time"), "07:05");
                assertEquals("07:05", page.evaluate("() => document.getElementById('time').value"), "overwrites");
                input.replaceText(page, page.locator("#date"), "2026-10-27");
                assertEquals("2026-10-27", page.evaluate("() => document.getElementById('date').value"));
                assertEquals("", selection(page), "date field: no page selection");

                assertThrows(RuntimeException.class, () -> input.replaceText(page, page.locator("#covered"), "x"));
                assertEquals("", selection(page), "covered field: no page selection");

                input.replaceText(page, page.locator("#name"), "Angelo");
                assertEquals("Angelo", page.evaluate("() => document.getElementById('name').value"));
                assertEquals("", selection(page), "normal field: selection stays inside the input");
            }
        }
    }

    private static String selection(Page page) {
        return String.valueOf(page.evaluate("() => String(getSelection())"));
    }
}
