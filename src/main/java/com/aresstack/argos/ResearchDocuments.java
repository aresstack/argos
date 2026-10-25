package com.aresstack.argos;

import com.aresstack.argos.batch.BatchEntry;
import com.aresstack.argos.batch.BatchOpener;
import com.aresstack.argos.document.DocumentStore;
import com.aresstack.argos.document.DocumentText;
import com.aresstack.argos.document.DocumentTextExtractor;
import com.aresstack.argos.document.StoredDocument;
import com.aresstack.argos.fetch.BackgroundTab;
import com.aresstack.argos.fetch.BrowserContextAccess;
import com.aresstack.argos.fetch.FetchFailure;
import com.aresstack.argos.fetch.FetchedResource;
import com.aresstack.argos.fetch.WebAddress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Documents and batches on top of the existing browser session: downloads in a background tab, text
 * extraction off the browser thread, and several pages per call. The current page is never navigated.
 */
public final class ResearchDocuments implements AutoCloseable {
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 120;
    private static final int BATCH_TIMEOUT_SECONDS = 240;
    private static final double COMMIT_TIMEOUT_MILLIS = 30_000;
    private static final double CONTENT_TIMEOUT_MILLIS = 75_000;

    private final BrowserContextAccess browser;
    private final DocumentStore store;
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();
    private final BatchOpener batch = new BatchOpener();

    public ResearchDocuments(BrowserContextAccess browser, DocumentStore store) {
        this.browser = browser;
        this.store = store;
    }

    /** Downloads {@code url} (absolute, or relative to the current page) and keeps it in the store. */
    public StoredDocument download(String url) {
        String target = WebAddress.requireHttp(url, browser.currentUrl());
        long limit = store.maxDocumentBytes();
        FetchedResource resource = unwrapping(() -> browser.inBrowserContext(context -> {
            try (BackgroundTab tab = BackgroundTab.open(context, target, COMMIT_TIMEOUT_MILLIS)) {
                return tab.content(limit, CONTENT_TIMEOUT_MILLIS);
            }
        }, DOWNLOAD_TIMEOUT_SECONDS));
        return store.store(resource);
    }

    /** Text of a stored document, at most {@code maxCharacters}. */
    public Map<String, Object> read(String downloadId, int maxCharacters) {
        StoredDocument document = store.find(downloadId).orElseThrow(() -> new FetchFailure(
                FetchFailure.Kind.NOT_FOUND, "Unknown or expired downloadId " + downloadId
                + "; download it again with browser_download"));
        int limit = Math.min(100_000, Math.max(1_000, maxCharacters));
        DocumentText text = extractor.extract(store.read(document), document.contentType(), document.filename(),
                limit);
        Map<String, Object> result = new LinkedHashMap<>(document.metadata());
        result.put("readable", text.readable());
        if (text.pages() > 0) result.put("pages", text.pages());
        if (text.readable()) {
            result.put("text", text.text());
            result.put("truncated", text.truncated());
        }
        if (!text.note().isEmpty()) result.put("note", text.note());
        return result;
    }

    /** Opens up to {@link BatchOpener#MAX_URLS} addresses in background tabs and reads each one. */
    public Map<String, Object> openMany(List<String> urls, int maxCharactersPerPage) {
        BatchEntry[] planned = BatchOpener.plan(urls);
        int perPage = Math.min(Math.max(200, maxCharactersPerPage), BatchOpener.MAX_TOTAL_CHARACTERS / urls.size());
        BatchOpener.Options options = BatchOpener.Options.defaults(perPage);
        long started = System.nanoTime();
        List<BatchEntry> loaded = unwrapping(() -> browser.inBrowserContext(
                context -> batch.load(context, urls, planned, options), BATCH_TIMEOUT_SECONDS));
        List<BatchEntry> entries = BatchOpener.extractDocuments(loaded, extractor, perPage);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parallelTabs", options.parallel());
        result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        result.put("succeeded", entries.stream().filter(e -> e.status() == BatchEntry.Status.SUCCESS).count());
        result.put("results", entries.stream().map(BatchEntry::toResult).toList());
        return result;
    }

    /** Restores the classified failure that the browser executor wrapped. */
    private static <T> T unwrapping(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof FetchFailure failure) throw failure;
            }
            throw error;
        }
    }

    @Override
    public void close() {
        store.close();
    }
}
