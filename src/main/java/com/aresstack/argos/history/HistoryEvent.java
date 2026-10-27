package com.aresstack.argos.history;

import java.time.Instant;
import java.util.List;

/**
 * One entry of a chat's canonical history ({@code events.jsonl}), in chronological order. {@code seq} counts the
 * chat's events from 1; the Lucene index remembers up to which {@code seq} it is current.
 */
public sealed interface HistoryEvent permits HistoryEvent.Search, HistoryEvent.PageVisit,
        HistoryEvent.NetworkResource {
    long seq();

    Instant at();

    /** The browser user context the call ran in; metadata only, never a filter. May be null. */
    String contextId();

    /** One successful search call: every call is its own event, identical queries are never merged. */
    record Search(long seq, Instant at, String query, String provider, String contextId, List<Result> results)
            implements HistoryEvent {
        public Search {
            query = query == null ? "" : query;
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    /** A search hit as the search returned it (rank from 1). It is no visited page. */
    record Result(int rank, String title, String url, String snippet) { }

    /**
     * One visit of a page; the page's content lives in the snapshot it points to, shared by every visit with the
     * same URL and content.
     *
     * @param status HTTP status, 0 = unknown
     * @param source the tool path that read the page, e.g. web_open, browser_snapshot, web_research
     */
    record PageVisit(long seq, Instant at, String url, String requestedUrl, String title, int status,
                     String contextId, String snapshotId, String contentHash, String source)
            implements HistoryEvent {
    }

    /**
     * One response the browser received during a regular browser operation (document, fetch, XHR, ...), whose body
     * is stored as resource {@code resourceId} ({@code resources/<resourceId>.body}, shared by every event with the
     * same stored bytes). No request body, no request or response headers.
     *
     * @param pageUrl      the tab's page when the body was read, null when unknown
     * @param category     the {@link ResourceCategory} key, e.g. content, images
     * @param resourceType the browser's resource type (document, fetch, xhr, image, ...)
     * @param size         stored bytes
     * @param complete     false for a partial (206 / Content-Range) or otherwise incomplete body
     * @param masked       true when secrets were removed from the text before it was stored
     * @param contentRange the Content-Range of a partial response, else null
     */
    record NetworkResource(long seq, Instant at, String url, String pageUrl, int status, String mimeType,
                           String category, String resourceType, String contextId, String resourceId,
                           String contentHash, long size, boolean complete, boolean masked, String contentRange)
            implements HistoryEvent {
    }
}
