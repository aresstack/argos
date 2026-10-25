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
            // The browser's own mode, and (on CDP) also the BiDi mode that may not rely on Download.cancel().
            java.util.Set<Boolean> modes = new java.util.LinkedHashSet<>(List.of(browser.canCancelDownloads(), false));
            for (boolean cancellable : modes) for (String path : new String[]{"/stalled-attachment", "/stalled.pdf"}) {
                long started = System.nanoTime();
                FetchFailure.Kind kind = browser.inBrowserContext(context -> {
                    try (BackgroundTab tab = BackgroundTab.open(context, site.url(path + "?mode=" + cancellable),
                            10_000, FixtureSite.testPolicy(), cancellable)) {
                        tab.content(1024 * 1024, 2_000);
                        return null;
                    } catch (FetchFailure failure) {
                        return failure.kind();
                    }
                }, 60);
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                assertEquals(FetchFailure.Kind.TIMEOUT, kind, path + " cancellable=" + cancellable);
                assertTrue(elapsed < 15_000, path + " cancellable=" + cancellable + " took " + elapsed + " ms");
            }
            browser.openCurrent(site.url("/other.html"));
            assertTrue(browser.currentText().contains("Another page"));
        }
    }
}
