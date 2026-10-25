package com.aresstack.argos.fetch;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in: a stalling server must not hold the single browser thread. */
class BackgroundTabDeadlineTest {
    @TempDir Path temporary;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void cancelsStalledDownloadsAndBodiesAndKeepsTheBrowserUsable() throws Exception {
        TestBrowserSession browser = TestBrowserSession.start(temporary).orElse(null);
        Assumptions.assumeTrue(browser != null, "Opt-in real browser test");
        try (browser; FixtureSite site = new FixtureSite()) {
            // Start the browser first: its launch and network self-test are not part of a fetch deadline.
            browser.inBrowserContext(context -> null, 120);
            // The browser's own mode, and (on CDP) also the BiDi mode that may not rely on Download.cancel().
            java.util.Set<Boolean> modes = new java.util.LinkedHashSet<>(List.of(browser.canCancelDownloads(), false));
            for (boolean cancellable : modes) for (String path : new String[]{"/stalled-attachment", "/stalled.pdf"}) {
                long started = System.nanoTime();
                // Phases in ms: open (new tab + navigation up to commit or download), content, close.
                long[] phases = new long[3];
                FetchFailure.Kind kind = browser.inBrowserContext(context -> {
                    long at = System.nanoTime();
                    BackgroundTab tab = BackgroundTab.open(context, site.url(path + "?mode=" + cancellable),
                            10_000, FixtureSite.testPolicy(), cancellable);
                    phases[0] = (System.nanoTime() - at) / 1_000_000;
                    try {
                        at = System.nanoTime();
                        tab.content(1024 * 1024, 2_000);
                        return null;
                    } catch (FetchFailure failure) {
                        return failure.kind();
                    } finally {
                        phases[1] = (System.nanoTime() - at) / 1_000_000;
                        at = System.nanoTime();
                        tab.close();
                        phases[2] = (System.nanoTime() - at) / 1_000_000;
                    }
                }, 60);
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                String measured = path + " cancellable=" + cancellable + " took " + elapsed + " ms (open "
                        + phases[0] + ", content " + phases[1] + ", close " + phases[2] + ")";
                System.out.println(measured);
                assertEquals(FetchFailure.Kind.TIMEOUT, kind, measured);
                assertTrue(elapsed < 15_000, measured);
            }
            browser.openCurrent(site.url("/other.html"));
            assertTrue(browser.currentText().contains("Another page"));
        }
    }
}
