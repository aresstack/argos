package com.aresstack.argos.web;

/**
 * What the browser delivers for one rendered document: final URL after redirects,
 * title, readable text, the serialized DOM (for link extraction on the Java side) and
 * the HTTP status of the document's response, 0 when it is not known.
 *
 * <p>The status comes from the navigation response the protocol reports, never from the
 * page; it is 0 for a document whose response the browser did not report (a history move
 * served from the cache, a tab the agent did not open through Argos).
 */
public record PageCapture(String url, String title, String text, String html, int status) {
    public PageCapture {
        url = url == null ? "" : url;
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        html = html == null ? "" : html;
        status = Math.max(0, status);
    }

    /** A capture whose response status is not known. */
    public PageCapture(String url, String title, String text, String html) {
        this(url, title, text, html, 0);
    }

    /** The same capture with {@code status} as its response status. */
    public PageCapture withStatus(int status) {
        return new PageCapture(url, title, text, html, status);
    }

    /** True when the server answered with an error status (400 or higher). */
    public boolean isError() {
        return status >= 400;
    }
}
