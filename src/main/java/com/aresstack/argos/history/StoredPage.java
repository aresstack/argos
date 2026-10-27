package com.aresstack.argos.history;

import java.time.Instant;
import java.util.List;

/**
 * One distinct page snapshot ({@code pages/<snapshotId>.json}): what a page said at a URL. A visit with the same
 * URL and the same readable content reuses it; changed content creates a new one.
 *
 * @param content  the readable page text (the searchable content, never the archived HTML)
 * @param links    absolute links extracted from the page
 * @param htmlNote why no single-file HTML exists ({@code pages/<snapshotId>.html}), or null
 */
public record StoredPage(String snapshotId, String url, String title, int status, String contentHash,
                         Instant capturedAt, String content, List<String> links, String htmlNote) {
    public StoredPage {
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        links = links == null ? List.of() : List.copyOf(links);
    }
}
