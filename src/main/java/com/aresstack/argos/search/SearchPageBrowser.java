package com.aresstack.argos.search;

/**
 * The browser seen by the search: load a page and hand back its serialized HTML, and click an element
 * with trusted input. Implementations must not inject page-visible scripts.
 */
public interface SearchPageBrowser {
    LoadedPage open(String url) throws Exception;

    LoadedPage current() throws Exception;

    /** Click the first element matching the selector at its bounding-box centre; false when not clickable. */
    boolean click(String cssSelector) throws Exception;

    record LoadedPage(String url, String title, int status, String html) {
    }
}
