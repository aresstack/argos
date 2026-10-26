package com.aresstack.argos.web;

/**
 * What the browser delivers for one rendered document: final URL after redirects,
 * title, readable text, the serialized DOM (for link extraction on the Java side) and
 * the HTTP status of the document's response, 0 when it is not known.
 *
 * <p>The status comes from the navigation response the protocol reports, never from the
 * page; it is 0 when no response is recorded for the shown address (a page that changed its
 * address by script after loading, or a capture built without a status).
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

    /** True when the server answered with an error status (400 or higher). */
    public boolean isError() {
        return status >= 400;
    }
}
