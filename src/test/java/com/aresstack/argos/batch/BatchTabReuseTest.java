package com.aresstack.argos.batch;

import com.aresstack.argos.fetch.FixtureSite;
import com.aresstack.argos.fetch.TestBrowserSession;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in (-PtestCamoufox=true or -PtestChromium=...): a batch opens at most {@code parallel} tabs and loads the
 * further addresses in tabs that finished, also after an error page, an attachment and a PDF; none stays open.
 */
class BatchTabReuseTest {
    @TempDir Path temporary;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aBatchReusesItsTabsAndClosesThemAtTheEnd() throws Exception {
        TestBrowserSession browser = TestBrowserSession.start(temporary).orElse(null);
        Assumptions.assumeTrue(browser != null, "Opt-in real browser test");
        try (browser; FixtureSite site = new FixtureSite()) {
            BatchOpener opener = new BatchOpener(FixtureSite.testPolicy(), browser.canCancelDownloads());
            List<String> urls = List.of(site.url("/article.html"), site.url("/missing"), site.url("/attachment"),
                    site.url("/other.html"), site.url("/paper.pdf"), site.url("/article.html?again"),
                    site.url("/other.html?again"));
            BatchOpener.Options options = new BatchOpener.Options(2, 20_000, 15_000, 2_000, 1024 * 1024);
            // Tabs that load something (CDP opens extra helper tabs for download deadlines; they load nothing).
            Set<Page> loading = Collections.newSetFromMap(new IdentityHashMap<>());
            int[] pagesBeforeAndAfter = new int[2];
            List<BatchEntry> entries = browser.inBrowserContext(context -> {
                Consumer<Page> count = page -> page.onRequest(request -> loading.add(page));
                context.onPage(count);
                pagesBeforeAndAfter[0] = context.pages().size();
                try {
                    return opener.load(context, urls, opener.plan(urls), options);
                } finally {
                    context.offPage(count);
                    pagesBeforeAndAfter[1] = context.pages().size();
                }
            }, 150);
            assertEquals(BatchEntry.Status.SUCCESS, entries.get(0).status(), entries.get(0).toString());
            assertEquals(BatchEntry.Status.NOT_FOUND, entries.get(1).status(), entries.get(1).toString());
            for (int i : new int[]{2, 3, 4, 5, 6}) {
                assertEquals(BatchEntry.Status.SUCCESS, entries.get(i).status(), entries.get(i).toString());
            }
            assertEquals("bericht.docx", entries.get(2).document().suggestedFilename(), entries.get(2).toString());
            assertTrue(entries.get(3).text().contains("Another page"), entries.get(3).toString());
            assertEquals("%PDF", new String(entries.get(4).document().content(), 0, 4, StandardCharsets.ISO_8859_1),
                    entries.get(4).toString());
            assertTrue(entries.get(5).text().contains("Research article"), entries.get(5).toString());
            assertTrue(loading.size() <= 2, "at most two tabs for seven addresses, used " + loading.size());
            assertEquals(pagesBeforeAndAfter[0], pagesBeforeAndAfter[1], "no batch tab stays open");
        }
    }

    /**
     * The next address never sees the previous page: a download that page starts late is not credited to it, and
     * an address that differs from where the tab ended up only by its fragment still loads as a new document.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void aReusedTabStartsFromABlankPage() throws Exception {
        TestBrowserSession browser = TestBrowserSession.start(temporary).orElse(null);
        Assumptions.assumeTrue(browser != null, "Opt-in real browser test");
        try (browser; FixtureSite site = new FixtureSite()) {
            BatchOpener opener = new BatchOpener(FixtureSite.testPolicy(), browser.canCancelDownloads());
            List<String> urls = List.of(site.url("/download-soon.html?ms=800"), site.url("/slow-head?ms=1500"),
                    site.url("/redirect?to=" + URLEncoder.encode(site.url("/other.html"), StandardCharsets.UTF_8)),
                    site.url("/other.html#part"));
            BatchOpener.Options options = new BatchOpener.Options(1, 20_000, 15_000, 2_000, 1024 * 1024);
            List<BatchEntry> entries = browser.inBrowserContext(
                    context -> opener.load(context, urls, opener.plan(urls), options), 100);
            assertNull(entries.get(1).document(), "the previous page's download: " + entries.get(1));
            assertTrue(entries.get(1).text().contains("late headers"), entries.get(1).toString());
            assertTrue(entries.get(3).text().contains("Another page"), entries.get(3).toString());
            for (BatchEntry entry : entries) {
                assertEquals(BatchEntry.Status.SUCCESS, entry.status(), entry.toString());
            }
        }
    }
}
