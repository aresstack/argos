package com.aresstack.argos;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Blank tabs nobody uses: the start tab Camoufox opens with its window, and the last tab of a context that was
 * emptied instead of closed. A load without a tab of its own takes one of these before it opens a new tab, so the
 * window does not keep an unused "New Tab" next to the pages. Each is handed out at most once; a tab that has
 * navigated since it became free (the user typed an address, an agent loaded a page) is never handed out.
 * Only used from the browser driver thread, which makes a claim atomic for parallel loads.
 */
final class FreeTabs {
    /** Each free tab with its top-level navigation count when it became free. */
    private final Map<Page, Integer> free = new LinkedHashMap<>();

    /** A blank tab that may be used; {@code navigations} is its navigation count now. */
    void offer(Page page, int navigations) {
        free.put(page, navigations);
    }

    /**
     * Takes the first tab of {@code context} that is still free: open, blank, and not navigated since it was
     * offered. Tabs found used are forgotten for good.
     */
    Optional<Page> claim(BrowserContext context, ToIntFunction<Page> navigations) {
        for (Iterator<Map.Entry<Page, Integer>> it = free.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Page, Integer> entry = it.next();
            Page page = entry.getKey();
            if (page.isClosed()) {
                it.remove();
                continue;
            }
            if (page.context() != context) continue; // another user context's tab stays free for that one
            it.remove();
            if ("about:blank".equals(page.url()) && navigations.applyAsInt(page) == entry.getValue()) {
                return Optional.of(page);
            }
        }
        return Optional.empty();
    }

    /** The tab is used by something else now (or closed). */
    void withdraw(Page page) {
        free.remove(page);
    }

    /** Forgets every tab, with the browser session they belonged to. */
    void clear() {
        free.clear();
    }
}
