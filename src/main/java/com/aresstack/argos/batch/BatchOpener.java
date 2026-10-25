package com.aresstack.argos.batch;

import com.aresstack.argos.document.DocumentText;
import com.aresstack.argos.document.DocumentTextExtractor;
import com.aresstack.argos.fetch.BackgroundTab;
import com.aresstack.argos.fetch.FetchFailure;
import com.aresstack.argos.fetch.FetchedResource;
import com.aresstack.argos.fetch.WebAddress;
import com.aresstack.argos.web.UrlPolicy;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads several addresses in background tabs of the existing session and reads each one.
 *
 * <p><b>What runs in parallel.</b> Playwright Java is not thread-safe, so Argos drives it from one thread and
 * every API call blocks that thread. Starting a navigation therefore costs one round trip up to the first
 * response headers (commit), one tab after the other. Everything after the commit (body, sub-resources, scripts,
 * rendering) proceeds in the browser for all open tabs at once while the thread waits for the oldest tab. Up to
 * {@code parallel} tabs are kept open in a sliding window. See {@code docs/batch-and-documents.md} for the
 * measurement.
 *
 * <p>Every address gets its own entry in request order; a failing address never fails the others.
 */
public final class BatchOpener {
    public static final int MAX_URLS = 10;
    public static final int MAX_PARALLEL = 4;
    public static final int MAX_TOTAL_CHARACTERS = 60_000;

    public record Options(int parallel, double commitTimeoutMillis, double loadTimeoutMillis,
                          int maxCharactersPerPage, long maxDocumentBytes) {
        public Options {
            parallel = Math.max(1, Math.min(MAX_PARALLEL, parallel));
            maxCharactersPerPage = Math.max(200, maxCharactersPerPage);
        }

        public static Options defaults(int maxCharactersPerPage) {
            return new Options(MAX_PARALLEL, 20_000, 15_000, maxCharactersPerPage, 10L * 1024 * 1024);
        }
    }

    private final UrlPolicy policy;

    public BatchOpener(UrlPolicy policy) {
        this.policy = policy;
    }

    /**
     * Validates, de-duplicates and admits {@code urls} by the URL policy; entries that need no browser are
     * filled in already.
     */
    public BatchEntry[] plan(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            throw new FetchFailure(FetchFailure.Kind.INVALID_REQUEST, "Give at least one URL");
        }
        if (urls.size() > MAX_URLS) {
            throw new FetchFailure(FetchFailure.Kind.INVALID_REQUEST, "At most " + MAX_URLS + " URLs per call");
        }
        BatchEntry[] entries = new BatchEntry[urls.size()];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < urls.size(); i++) {
            String requested = urls.get(i) == null ? "" : urls.get(i).trim();
            try {
                String url = WebAddress.requireHttp(requested, null);
                WebAddress.admit(policy, url);
                if (!seen.add(WebAddress.withoutFragment(url))) {
                    entries[i] = BatchEntry.skipped(requested, BatchEntry.Status.DUPLICATE,
                            "Same address as an earlier entry");
                }
            } catch (FetchFailure refused) {
                entries[i] = BatchEntry.skipped(requested, BatchEntry.Status.valueOf(refused.kind().name()),
                        refused.getMessage());
            }
        }
        return entries;
    }

    /**
     * Loads every address of {@code urls} whose entry in {@code planned} is still empty. Runs on the browser
     * driver thread. Non-HTML resources keep their bytes for {@link #extractDocuments}.
     */
    public List<BatchEntry> load(BrowserContext context, List<String> urls, BatchEntry[] planned, Options options) {
        BatchEntry[] entries = Arrays.copyOf(planned, planned.length);
        Deque<Integer> waiting = new ArrayDeque<>();
        for (int i = 0; i < entries.length; i++) if (entries[i] == null) waiting.add(i);
        Deque<OpenTab> open = new ArrayDeque<>();
        try {
            while (!waiting.isEmpty() || !open.isEmpty()) {
                while (!waiting.isEmpty() && open.size() < options.parallel()) {
                    int index = waiting.poll();
                    open.add(OpenTab.start(context, index, urls.get(index).trim(), options, policy));
                }
                OpenTab oldest = open.poll();
                try {
                    entries[oldest.index] = read(oldest, options);
                } finally {
                    oldest.tab.close();
                }
            }
        } finally {
            open.forEach(remaining -> remaining.tab.close());
        }
        return List.of(entries);
    }

    /** Extracts the text of non-HTML resources. Runs off the browser thread (PDF parsing can take a while). */
    public static List<BatchEntry> extractDocuments(List<BatchEntry> entries, DocumentTextExtractor extractor,
                                                    int maxCharactersPerPage) {
        List<BatchEntry> finished = new ArrayList<>(entries.size());
        for (BatchEntry entry : entries) {
            FetchedResource document = entry.document();
            if (document == null) {
                finished.add(entry);
                continue;
            }
            DocumentText text = extractor.extract(document.content(), document.contentType(),
                    document.suggestedFilename().isBlank() ? document.finalUrl() : document.suggestedFilename(),
                    maxCharactersPerPage);
            finished.add(text.readable()
                    ? entry.withText(text.text(), text.truncated(), BatchEntry.Status.SUCCESS, "")
                    : entry.withText("", false, BatchEntry.Status.EXTRACTION_FAILED, text.note()));
        }
        return finished;
    }

    private static BatchEntry read(OpenTab open, Options options) {
        BackgroundTab tab = open.tab;
        String url = open.url;
        FetchFailure failure = tab.failure();
        if (failure != null) return failed(open, failure);
        try {
            if (tab.isHtmlPage()) {
                boolean complete = tab.awaitLoad(options.loadTimeoutMillis());
                String text = tab.renderedText();
                boolean truncated = text.length() > options.maxCharactersPerPage();
                if (truncated) text = text.substring(0, options.maxCharactersPerPage());
                return new BatchEntry(url, BatchEntry.Status.SUCCESS, WebAddress.withoutUserInfo(tab.finalUrl()),
                        tab.status(), tab.contentType(), tab.title(), text, truncated, complete, "",
                        open.elapsed(), null);
            }
            FetchedResource document = tab.content(options.maxDocumentBytes(), options.loadTimeoutMillis());
            return new BatchEntry(url, BatchEntry.Status.SUCCESS, WebAddress.withoutUserInfo(document.finalUrl()),
                    tab.status(), tab.contentType(), "", "", false, true, "", open.elapsed(), document);
        } catch (FetchFailure known) {
            return failed(open, known);
        } catch (PlaywrightException error) {
            return failed(open, new FetchFailure(FetchFailure.Kind.FAILED, "The tab could not be read"));
        }
    }

    private static BatchEntry failed(OpenTab open, FetchFailure failure) {
        return new BatchEntry(open.url, BatchEntry.Status.valueOf(failure.kind().name()), "",
                open.tab.status(), "", "", "", false, false, failure.getMessage(), open.elapsed(), null);
    }

    private record OpenTab(int index, String url, BackgroundTab tab, long startedNanos) {
        static OpenTab start(BrowserContext context, int index, String url, Options options, UrlPolicy policy) {
            long started = System.nanoTime();
            return new OpenTab(index, url, BackgroundTab.open(context, url, options.commitTimeoutMillis(), policy),
                    started);
        }

        long elapsed() {
            return (System.nanoTime() - startedNanos) / 1_000_000;
        }
    }
}
