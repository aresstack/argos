package com.aresstack.argos.web;

/**
 * The browser operations the web_* tools need. Implementations must not inject
 * page-visible scripts: reading happens in Playwright's isolated utility world and
 * navigation goes through the browser itself.
 */
public interface BrowserPagePort {

    /** Navigate to {@code url}; {@code referer} is the page the agent came from, or null. */
    PageCapture open(String url, String referer);

    /** Read the current document again without navigating. */
    PageCapture current();

    /**
     * The address the browser shows now, without reading the page. Other tools (e.g. browser_navigate,
     * web_click) can change it behind the web session's back.
     */
    default String currentAddress() {
        PageCapture current = current();
        return current == null ? "" : current.url();
    }

    /** Go back one history entry. @return the previous page, or null without history. */
    PageCapture back();

    /** Leave a page the agent must not see (e.g. a blocked redirect target) for about:blank. */
    void clear();
}
