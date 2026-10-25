package com.aresstack.argos.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The research session behind the compact web_* tools, modeled on AskAI's
 * BrowserSession. Every answer is short numbered plain text, which small models
 * follow more reliably than nested JSON. Navigation is a real browser navigation
 * with the current page as referer; no script is injected into the page.
 */
public final class WebSession {
    public static final int DEFAULT_PART_CHARACTERS = 12_000;
    public static final int DEFAULT_MAX_LINKS = 150;
    private static final String SEARCH_PAGE = "search:";

    private final BrowserPagePort browser;
    private final UrlPolicy policy;
    private final WebSearchPort search;
    private final int partCharacters;
    private final LinkExtractor extractor = new LinkExtractor();
    private final LinkRegistry links;
    private PageCapture page;

    public WebSession(BrowserPagePort browser, UrlPolicy policy, WebSearchPort search) {
        this(browser, policy, search, DEFAULT_PART_CHARACTERS, DEFAULT_MAX_LINKS);
    }

    public WebSession(BrowserPagePort browser, UrlPolicy policy, WebSearchPort search,
                      int partCharacters, int maxLinks) {
        this.browser = browser;
        this.policy = policy;
        this.search = search == null ? new UnavailableWebSearch() : search;
        this.partCharacters = partCharacters;
        this.links = new LinkRegistry(maxLinks);
    }

    public synchronized String search(String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search query.");
        SearchResults results = search.search(query.trim());
        links.update(SEARCH_PAGE + query.trim(), results.hits().stream()
                .map(hit -> new LinkExtractor.Anchor(hit.title(), hit.url())).toList());
        Map<String, String> snippets = new HashMap<>();
        results.hits().forEach(hit -> snippets.putIfAbsent(hit.url(), hit.snippet()));
        StringBuilder out = new StringBuilder();
        List<PageLink> registered = links.links();
        for (int i = 0; i < registered.size(); i++) {
            PageLink hit = registered.get(i);
            out.append(i + 1).append(": ").append(hit.text()).append(" — ").append(hit.url()).append('\n');
            String snippet = snippets.get(hit.url());
            if (snippet != null && !snippet.isBlank()) out.append("   ").append(snippet.strip()).append('\n');
        }
        if (registered.isEmpty()) out.append("No results.\n");
        else out.append("Open a result with web_follow link_id=\"1\" or web_open url=\"...\".\n");
        results.notes().forEach(note -> out.append(note).append('\n'));
        return out.toString().stripTrailing();
    }

    public synchronized String open(String url) {
        policy.check(url);
        String referer = page == null ? null : page.url();
        return show(accept(browser.open(url.trim(), referer)), 1);
    }

    public synchronized String read(int part) {
        requirePage();
        return show(accept(browser.current()), part);
    }

    /** Links of the last capture; no extra browser read (web_read refreshes late-loaded links). */
    public synchronized String links() {
        requirePage();
        List<PageLink> current = links.links();
        if (current.isEmpty()) return "No links on " + page.url();
        StringBuilder out = new StringBuilder();
        for (PageLink link : current) {
            out.append(link.id()).append(": ")
                    .append(link.text().isEmpty() ? "(no text)" : link.text())
                    .append(" — ").append(link.url()).append('\n');
        }
        if (links.truncated()) out.append("(more links not shown)\n");
        return out.toString().stripTrailing();
    }

    public synchronized String follow(String linkId) {
        PageLink link = links.find(linkId);
        if (link == null) {
            throw new IllegalArgumentException("Unknown link id: " + linkId
                    + ". Link ids belong to the current page; call web_links first.");
        }
        return open(link.url());
    }

    public synchronized String back() {
        requirePage();
        PageCapture previous = browser.back();
        if (previous == null) throw new IllegalStateException("No previous page in history.");
        return show(accept(previous), 1);
    }

    /** Re-checks the final URL after redirects and refreshes the link ids. */
    private PageCapture accept(PageCapture capture) {
        try {
            policy.check(capture.url());
        } catch (IllegalArgumentException blocked) {
            page = null;
            links.clear();
            try {
                browser.back();
            } catch (RuntimeException ignored) {
                // Without history the session simply has no current page anymore.
            }
            throw new IllegalArgumentException("Blocked after redirect: " + blocked.getMessage());
        }
        page = capture;
        links.update(capture.url(), extractor.extract(capture.html(), capture.url()));
        return capture;
    }

    private String show(PageCapture capture, int part) {
        String text = capture.text().strip();
        int parts = Math.max(1, (text.length() + partCharacters - 1) / partCharacters);
        if (part < 1 || part > parts) {
            throw new IllegalArgumentException("This page has " + parts + " part(s); use part 1 to " + parts + ".");
        }
        int start = (part - 1) * partCharacters;
        String slice = text.substring(start, Math.min(text.length(), start + partCharacters));
        StringBuilder out = new StringBuilder()
                .append("URL: ").append(capture.url()).append('\n')
                .append("TITLE: ").append(capture.title()).append('\n')
                .append("LINKS: ").append(links.links().size()).append(" (see web_links)").append('\n');
        if (parts > 1) {
            out.append("PART: ").append(part).append(" of ").append(parts);
            if (part < parts) out.append(" (next: web_read part=").append(part + 1).append(')');
            out.append('\n');
        }
        return out.append('\n').append(slice).toString();
    }

    private void requirePage() {
        if (page == null) throw new IllegalStateException("No page is open yet. Call web_open or web_search first.");
    }
}
