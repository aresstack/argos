package com.aresstack.argos.web;

/**
 * What the browser delivers for one rendered document: final URL after redirects,
 * title, readable text and the serialized DOM (for link extraction on the Java side).
 */
public record PageCapture(String url, String title, String text, String html) {
    public PageCapture {
        url = url == null ? "" : url;
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        html = html == null ? "" : html;
    }
}
