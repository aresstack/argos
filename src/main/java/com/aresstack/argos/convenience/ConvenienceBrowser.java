package com.aresstack.argos.convenience;

import com.aresstack.argos.web.PageCapture;

import java.util.List;

/**
 * The tab-addressed browser operations of the convenience profile. Every call names its tab; nothing relies on
 * the browser's current tab. Implemented by CamoufoxBrowser; tests use doubles over local HTTP servers.
 */
public interface ConvenienceBrowser {
    /** One page to load: in an existing tab, or (tab null) in a new tab that does not become current. */
    record TabLoadRequest(String tab, String url, String domain) {
    }

    enum TabLoadOutcome { OK, DOWNLOAD, FAILED, TIMEOUT, COOLING_DOWN }

    /**
     * What a tab shows after a load or read. {@code capture} is null unless the outcome is OK; {@code challenge}
     * marks a recognizable access challenge; {@code retryAfterSeconds} is the site's Retry-After (or the remaining
     * cooldown for COOLING_DOWN). The final URL after redirects is {@code capture.url()}.
     */
    record TabLoad(String tab, String requestedUrl, TabLoadOutcome outcome, PageCapture capture,
                   String contentType, long retryAfterSeconds, boolean challenge, String error) {
    }

    /**
     * Loads the pages with overlap: each starts once {@code gate} grants its domain and fewer than {@code parallel}
     * loads run. Results keep the order of {@code requests}; a failure stays local to its page.
     */
    List<TabLoad> loadTabs(List<TabLoadRequest> requests, NavigationGate gate, int parallel, long timeoutMillis);

    /** Reads a tab again without navigating. */
    TabLoad readTab(String tab);

    /** One history step back in the tab, read; null without history. The caller holds the domain slot. */
    TabLoad backTab(String tab);

    /** A real (BiDi input) click in the tab; false when the element is missing. */
    boolean clickInTab(String selector, String tab);

    /**
     * Searches in the tab like a person: a real click into the first present field among {@code searchBoxSelectors},
     * the query typed key by key and a real Enter (BiDi input, no script submit); the tab is read once the result
     * page has loaded. Null when no selector matches; an exception when a field matched but could not take the
     * input. The caller holds the domain slot.
     */
    TabLoad submitSearchInTab(String tab, List<String> searchBoxSelectors, String query);

    boolean hasOpenTab(String tab);

    /** Closes the tab; the context's last tab is emptied instead. */
    void closeTabQuietly(String tab);

    /** The browser context the calls go to; references never cross contexts. */
    String contextId();
}
