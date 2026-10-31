package com.aresstack.argos.search;

import java.util.List;

/**
 * The browser seen by the search: load a page and hand back its serialized HTML, click an element and type into a
 * search field with trusted input. Implementations must not inject page-visible scripts.
 */
public interface SearchPageBrowser {
    LoadedPage open(String url) throws Exception;

    LoadedPage current() throws Exception;

    /** Click the first element matching the selector at its bounding-box centre; false when not clickable. */
    boolean click(String cssSelector) throws Exception;

    /**
     * Operates the current page's search field as a person does: a real click into the first field among
     * {@code searchBoxSelectors} that is present, its content replaced by typing {@code query} key by key, then a
     * real Enter key, all as WebDriver BiDi input; waits for the navigation that follows. Never submits a form or
     * dispatches events by script. False when none of the selectors matches; an exception when a field matched but
     * could not take the input (covered, not focusable, untypeable text).
     */
    boolean submitQuery(List<String> searchBoxSelectors, String query) throws Exception;

    record LoadedPage(String url, String title, int status, String html) {
    }
}
