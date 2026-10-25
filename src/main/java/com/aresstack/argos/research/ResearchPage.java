package com.aresstack.argos.research;

import java.util.List;

/**
 * One page as the research sees it: where the browser landed, its readable text and its links.
 *
 * @param url    final URL after redirects
 * @param access whether the site let the browser in
 */
public record ResearchPage(String url, String title, int status, String text, List<PageLink> links,
                           Access access) {
    public enum Access { READABLE, BLOCKED, CHALLENGE }

    public ResearchPage {
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        links = List.copyOf(links);
    }

    /** An absolute http(s) link with the words the page uses for it. */
    public record PageLink(String text, String url) {
    }
}
