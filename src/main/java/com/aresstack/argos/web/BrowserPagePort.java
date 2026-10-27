package com.aresstack.argos.web;

import java.util.List;

/**
 * The browser operations the web_* tools need. Implementations must not inject
 * page-visible scripts: reading happens in Playwright's isolated utility world and
 * navigation goes through the browser itself.
 *
 * <p>The tab-aware methods take a tab id ({@code t2}); null means the tab the browser shows. A browser with a
 * single page keeps the defaults: it reports one tab and cannot open more, so research stays sequential.
 */
public interface BrowserPagePort {
    /** The id a single-page browser reports for its only tab. */
    String SINGLE_TAB = "t1";

    /** Navigate to {@code url}; {@code referer} is the page the agent came from, or null. */
    PageCapture open(String url, String referer);

    /** Read the current document again without navigating. */
    PageCapture current();

    /** The address the browser shows ({@code about:blank} for a blank tab), or "" before it started. */
    String currentUrl();

    /** Go back one history entry. @return the previous page, or null without history. */
    PageCapture back();

    /** Leave a page the agent must not see (e.g. a blocked redirect target) for about:blank. */
    void clear();

    /** One page opened by {@link #openInNewTabs}: its tab and capture, or the error that stopped it. */
    record TabPage(String tab, PageCapture capture, String error) {
    }

    /** True when the browser can hold several tabs ({@link #newTab}, {@link #openInNewTabs}). */
    default boolean supportsTabs() {
        return false;
    }

    /** The id of the tab the browser shows. */
    default String currentTab() {
        return SINGLE_TAB;
    }

    /** True while the tab is open. */
    default boolean hasTab(String tab) {
        return SINGLE_TAB.equals(tab);
    }

    default PageCapture open(String url, String referer, String tab) {
        requireSingle(tab);
        return open(url, referer);
    }

    default PageCapture current(String tab) {
        requireSingle(tab);
        return current();
    }

    default String currentUrl(String tab) {
        return tab == null || SINGLE_TAB.equals(tab) ? currentUrl() : "";
    }

    default PageCapture back(String tab) {
        requireSingle(tab);
        return back();
    }

    default void clear(String tab) {
        requireSingle(tab);
        clear();
    }

    /** Makes {@code tab} the tab the browser shows; nothing is read from the page. */
    default void showTab(String tab) {
        requireSingle(tab);
    }

    /** Opens a blank tab without selecting it. @return its id */
    default String newTab() {
        throw new UnsupportedOperationException("This browser has a single tab.");
    }

    /** Closes {@code tab}; the last tab is never closed (callers clear it instead). */
    default void discardTab(String tab) {
        throw new UnsupportedOperationException("This browser has a single tab.");
    }

    /**
     * Opens every URL exactly as given in a new tab, at most {@code parallel} navigations in flight; the results
     * keep the order of {@code urls} and a failure stays local to its page. Nothing is opened in parallel by
     * default: a browser without tabs refuses.
     */
    default List<TabPage> openInNewTabs(List<String> urls, int parallel) {
        throw new UnsupportedOperationException("This browser has a single tab.");
    }

    private static void requireSingle(String tab) {
        if (tab != null && !SINGLE_TAB.equals(tab)) {
            throw new IllegalArgumentException("Unknown tab " + tab + "; this browser has the single tab " + SINGLE_TAB + ".");
        }
    }

}
