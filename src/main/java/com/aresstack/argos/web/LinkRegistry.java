package com.aresstack.argos.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands out short link ids (link-1, link-2, ...) for the page each tab shows and keeps each link's exact URL,
 * which the agent never sees. Ids stay stable while a tab stays on the same page: re-reading a page that loaded
 * more content keeps every known id and appends new links at the end. Ids are never reused within a session and
 * are shared by all tabs, so an id names its tab by itself, and an id of an earlier page or a closed tab is
 * recognized as stale instead of silently pointing to a link elsewhere.
 */
final class LinkRegistry {
    /** A link of a tab's current page. */
    record TabLink(String tab, PageLink link) {
    }

    private static final class TabLinks {
        final Map<String, PageLink> byUrl = new LinkedHashMap<>();
        String pageUrl = "";
        boolean truncated;
    }

    private final int maxLinks;
    private final Map<String, TabLinks> tabs = new HashMap<>();
    /** Number of the next new id; never goes back. */
    private int next = 1;

    LinkRegistry(int maxLinks) {
        this.maxLinks = maxLinks;
    }

    /** Record the links of {@code url} in {@code tab}; a new page of the tab gets new ids. */
    void update(String tab, String url, List<LinkExtractor.Anchor> anchors) {
        TabLinks state = tabs.computeIfAbsent(tab, any -> new TabLinks());
        if (!url.equals(state.pageUrl)) {
            state.byUrl.clear();
            state.pageUrl = url;
        }
        state.truncated = false;
        for (LinkExtractor.Anchor anchor : anchors) {
            PageLink known = state.byUrl.get(anchor.url());
            if (known != null) {
                if (known.text().isEmpty() && !anchor.text().isEmpty()) {
                    state.byUrl.put(anchor.url(), new PageLink(known.id(), anchor.text(), anchor.url()));
                }
                continue;
            }
            if (state.byUrl.size() >= maxLinks) {
                state.truncated = true;
                continue;
            }
            state.byUrl.put(anchor.url(), new PageLink("link-" + next++, anchor.text(), anchor.url()));
        }
    }

    /** Forgets the links of {@code tab}; their ids become stale. */
    void clear(String tab) {
        tabs.remove(tab);
    }

    List<PageLink> links(String tab) {
        TabLinks state = tabs.get(tab);
        return state == null ? List.of() : new ArrayList<>(state.byUrl.values());
    }

    boolean truncated(String tab) {
        TabLinks state = tabs.get(tab);
        return state != null && state.truncated;
    }

    /** True when link-{@code number} was handed out but belongs to no tab's current page any more. */
    boolean stale(int number) {
        return number >= 1 && number < next && find(number) == null;
    }

    /** The link with id link-{@code number} and the tab whose page has it, or null. */
    TabLink find(int number) {
        String id = "link-" + number;
        for (Map.Entry<String, TabLinks> tab : tabs.entrySet()) {
            for (PageLink link : tab.getValue().byUrl.values()) {
                if (link.id().equals(id)) return new TabLink(tab.getKey(), link);
            }
        }
        return null;
    }
}
