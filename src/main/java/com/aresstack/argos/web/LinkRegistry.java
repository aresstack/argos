package com.aresstack.argos.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hands out short link ids (link-1, link-2, ...) for the current page. Ids stay stable
 * while the agent stays on the same page: re-reading a page that loaded more content
 * keeps every known id and appends new links at the end. Navigating elsewhere starts a
 * fresh numbering, so an id never silently points to a link on another page.
 */
final class LinkRegistry {
    private final int maxLinks;
    private final Map<String, PageLink> byUrl = new LinkedHashMap<>();
    private String pageUrl = "";
    private boolean truncated;

    LinkRegistry(int maxLinks) {
        this.maxLinks = maxLinks;
    }

    /** Record the links of {@code url}; restarts the numbering when the page changed. */
    void update(String url, List<LinkExtractor.Anchor> anchors) {
        if (!url.equals(pageUrl)) {
            byUrl.clear();
            pageUrl = url;
        }
        truncated = false;
        for (LinkExtractor.Anchor anchor : anchors) {
            PageLink known = byUrl.get(anchor.url());
            if (known != null) {
                if (known.text().isEmpty() && !anchor.text().isEmpty()) {
                    byUrl.put(anchor.url(), new PageLink(known.id(), anchor.text(), anchor.url()));
                }
                continue;
            }
            if (byUrl.size() >= maxLinks) {
                truncated = true;
                continue;
            }
            byUrl.put(anchor.url(), new PageLink("link-" + (byUrl.size() + 1), anchor.text(), anchor.url()));
        }
    }

    void clear() {
        byUrl.clear();
        pageUrl = "";
        truncated = false;
    }

    List<PageLink> links() {
        return new ArrayList<>(byUrl.values());
    }

    boolean truncated() {
        return truncated;
    }

    /** Accepts "link-3", "3", "Link 3" and "[3]" — small models are not always exact. */
    PageLink find(String id) {
        String wanted = normalize(id);
        for (PageLink link : byUrl.values()) {
            if (normalize(link.id()).equals(wanted)) return link;
        }
        return null;
    }

    private static String normalize(String id) {
        String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[\\[\\]#\"']", "").replaceFirst("^link[-_ ]?", "");
        return value.trim();
    }
}
