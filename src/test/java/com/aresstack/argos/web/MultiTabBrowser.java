package com.aresstack.argos.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test double for a browser with tabs: each tab keeps its own history, pages are fetched over plain HTTP. */
final class MultiTabBrowser implements BrowserPagePort {
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(HttpClient.Builder.NO_PROXY).build();
    private final Map<String, Deque<String>> tabs = new LinkedHashMap<>();
    private int nextTab = 1;
    private String current;
    /** The URL lists of every openInNewTabs call: one entry per wave of parallel loads. */
    final List<List<String>> waves = new ArrayList<>();
    /** Every URL a tab navigated to, exactly as handed to the browser. */
    final List<String> navigated = new ArrayList<>();

    MultiTabBrowser() {
        current = newTab();
    }

    @Override public boolean supportsTabs() { return true; }

    @Override public String currentTab() { return current; }

    @Override public boolean hasTab(String tab) { return tabs.containsKey(tab); }

    @Override public PageCapture open(String url, String referer) { return open(url, referer, null); }

    @Override public PageCapture open(String url, String referer, String tab) {
        String id = tab == null ? current : tab;
        PageCapture page = fetch(url);
        history(id).push(page.url());
        return page;
    }

    @Override public PageCapture current() { return current(null); }

    @Override public PageCapture current(String tab) {
        return fetch(history(tab == null ? current : tab).peek());
    }

    @Override public String currentUrl() { return currentUrl(null); }

    @Override public String currentUrl(String tab) {
        Deque<String> history = tabs.get(tab == null ? current : tab);
        return history == null || history.isEmpty() ? "" : history.peek();
    }

    @Override public PageCapture back() { return back(null); }

    @Override public PageCapture back(String tab) {
        Deque<String> history = history(tab == null ? current : tab);
        if (history.size() < 2) return null;
        history.pop();
        return fetch(history.peek());
    }

    @Override public void clear() { clear(null); }

    @Override public void clear(String tab) { history(tab == null ? current : tab).clear(); }

    @Override public void showTab(String tab) {
        history(tab);
        current = tab;
    }

    @Override public String newTab() {
        String id = "t" + nextTab++;
        tabs.put(id, new ArrayDeque<>());
        return id;
    }

    @Override public void discardTab(String tab) {
        if (tabs.size() <= 1) throw new IllegalArgumentException("The last tab stays open; navigate it instead.");
        tabs.remove(tab);
        if (tab.equals(current)) current = tabs.keySet().iterator().next();
    }

    @Override public List<TabPage> openInNewTabs(List<String> urls, int parallel) {
        waves.add(List.copyOf(urls));
        List<TabPage> pages = new ArrayList<>();
        for (String url : urls) {
            String tab = newTab();
            try {
                pages.add(new TabPage(tab, open(url, null, tab), ""));
            } catch (RuntimeException failed) {
                pages.add(new TabPage(tab, null, failed.getMessage()));
            }
        }
        return pages;
    }

    /** Another tool moves a tab; the web session is not told. */
    void moveTo(String tab, String url) {
        history(tab).push(fetch(url).url());
    }

    private Deque<String> history(String tab) {
        Deque<String> history = tabs.get(tab);
        if (history == null) throw new IllegalArgumentException("Unknown tab " + tab);
        return history;
    }

    private PageCapture fetch(String url) {
        navigated.add(url);
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return PageText.capture(response.uri().toString(), response.body(), response.statusCode());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
