package com.aresstack.argos;

import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stable short ids ({@code t1}, {@code t2}, ...) for the pages of the one persistent browser context, and which
 * of them is current. Every tab opened by a website (for example {@code target=_blank}) is adopted as well.
 * Only used from the browser driver thread.
 */
final class BrowserTabs {
    private final Map<String, Page> tabs = new LinkedHashMap<>();
    private int nextId = 1;
    private String current;

    /** Registers {@code page} once and returns its id. The first tab becomes current. */
    String adopt(Page page) {
        Optional<String> known = idOf(page);
        if (known.isPresent()) return known.get();
        String id = "t" + nextId++;
        tabs.put(id, page);
        if (current == null) current = id;
        return id;
    }

    /** Forgets a closed page; if it was current, the most recently opened remaining tab becomes current. */
    void forget(Page page) {
        idOf(page).ifPresent(id -> {
            tabs.remove(id);
            if (id.equals(current)) {
                current = tabs.isEmpty() ? null : new ArrayList<>(tabs.keySet()).get(tabs.size() - 1);
            }
        });
    }

    /** Forgets every tab, after the browser was restarted. Ids keep counting up. */
    void clear() {
        tabs.clear();
        current = null;
    }

    Optional<Page> current() {
        prune();
        return current == null ? Optional.empty() : Optional.ofNullable(tabs.get(current));
    }

    String currentId() {
        prune();
        return current;
    }

    Page select(String id) {
        prune();
        Page page = tabs.get(normalize(id));
        if (page == null) throw new IllegalArgumentException("Unknown tab " + id + "; open tabs: " + tabs.keySet());
        current = normalize(id);
        return page;
    }

    Page get(String id) {
        prune();
        Page page = tabs.get(normalize(id));
        if (page == null) throw new IllegalArgumentException("Unknown tab " + id + "; open tabs: " + tabs.keySet());
        return page;
    }

    Optional<String> idOf(Page page) {
        return tabs.entrySet().stream().filter(entry -> entry.getValue() == page).map(Map.Entry::getKey).findFirst();
    }

    /** Tabs in opening order. */
    Map<String, Page> all() {
        prune();
        return new LinkedHashMap<>(tabs);
    }

    int size() {
        prune();
        return tabs.size();
    }

    private void prune() {
        List<Page> closed = tabs.values().stream().filter(Page::isClosed).toList();
        closed.forEach(this::forget);
    }

    /** Accepts {@code t2}, {@code 2} and {@code T2}. */
    static String normalize(String id) {
        String trimmed = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.matches("\\d+") ? "t" + trimmed : trimmed;
    }
}
