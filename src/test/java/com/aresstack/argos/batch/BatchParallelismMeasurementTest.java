package com.aresstack.argos.batch;

import com.aresstack.argos.fetch.FixtureSite;
import com.aresstack.argos.fetch.TestBrowserSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures what {@link BatchOpener} really runs in parallel on Argos' single Playwright thread, with local
 * servers that delay either the response headers (before commit) or the rest of the body (after commit).
 * Opt-in: needs -PtestCamoufox=true or -PtestChromium=/path/to/chrome. Writes build/reports/argos/.
 */
class BatchParallelismMeasurementTest {
    private static final int TABS = 4;
    private static final int DELAY_MILLIS = 1_500;

    @TempDir Path temporary;

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void measuresSequentialAgainstSlidingWindow() throws Exception {
        TestBrowserSession browser = TestBrowserSession.start(temporary).orElse(null);
        Assumptions.assumeTrue(browser != null, "Opt-in real browser measurement");
        try (browser; FixtureSite site = new FixtureSite()) {
            BatchOpener opener = new BatchOpener(FixtureSite.testPolicy(), browser.canCancelDownloads());
            // Warm-up so browser start and first-tab costs do not count.
            run(browser, opener, site, "/other.html", 1, 1);

            long overhead = run(browser, opener, site, "/other.html", 1, TABS);
            long[] tabCost = rawTabCost(browser);
            long bodySequential = run(browser, opener, site, "/slow-body", 1, TABS);
            long bodyParallel = run(browser, opener, site, "/slow-body", TABS, TABS);
            long headSequential = run(browser, opener, site, "/slow-head", 1, TABS);
            long headParallel = run(browser, opener, site, "/slow-head", TABS, TABS);
            long overlapped = bodySequential - bodyParallel;

            String report = """
                    engine: %s
                    tabs per run: %d, server delay per address: %d ms
                    no delay (per-tab cost of open/read/close): %5d ms for %d tabs one after the other
                    of that, bare context.newPage(): %5d ms, page.close(): %5d ms (sum over %d tabs)
                    delay after commit (slow body):     sequential %5d ms | %d parallel tabs %5d ms | saved %d ms
                    delay before commit (slow headers): sequential %5d ms | %d parallel tabs %5d ms
                    """.formatted(browser.engine(), TABS, DELAY_MILLIS, overhead, TABS, tabCost[0], tabCost[1], TABS, bodySequential, TABS,
                    bodyParallel, overlapped, headSequential, TABS, headParallel);
            Path out = Path.of("build", "reports", "argos");
            Files.createDirectories(out);
            Files.writeString(out.resolve("batch-parallelism-" + browser.engine() + ".txt"), report);
            System.out.println(report);

            assertTrue(bodySequential >= TABS * DELAY_MILLIS, report);
            // Only the waiting after commit can overlap; the per-tab cost before commit (opening the tab,
            // connecting, closing) stays serial on the single Playwright thread. Of the (TABS - 1) delays
            // that can overlap, at least 60 % must actually have been saved.
            assertTrue(overlapped >= (TABS - 1) * DELAY_MILLIS * 0.6, report);
        }
    }

    /** Only opening and closing blank tabs, to tell browser tab costs apart from loading and reading. */
    private static long[] rawTabCost(TestBrowserSession browser) {
        return browser.inBrowserContext(context -> {
            long opening = 0;
            long closing = 0;
            for (int i = 0; i < TABS; i++) {
                long started = System.nanoTime();
                com.microsoft.playwright.Page page = context.newPage();
                long opened = System.nanoTime();
                page.close();
                opening += opened - started;
                closing += System.nanoTime() - opened;
            }
            return new long[]{opening / 1_000_000, closing / 1_000_000};
        }, 120);
    }

    private static long run(TestBrowserSession browser, BatchOpener opener, FixtureSite site, String path,
                            int parallel, int count) {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < count; i++) urls.add(site.url(path + "?ms=" + DELAY_MILLIS + "&n=" + i));
        BatchOpener.Options options = new BatchOpener.Options(parallel, 20_000, 15_000, 1_000, 1024 * 1024);
        long started = System.nanoTime();
        List<BatchEntry> entries = browser.inBrowserContext(
                context -> opener.load(context, urls, opener.plan(urls), options), 120);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        for (BatchEntry entry : entries) {
            assertTrue(entry.status() == BatchEntry.Status.SUCCESS && entry.loadComplete(), entry.toString());
        }
        return elapsed;
    }
}
