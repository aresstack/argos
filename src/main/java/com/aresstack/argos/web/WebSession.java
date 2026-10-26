package com.aresstack.argos.web;

import com.aresstack.argos.fetch.WebAddress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The research session behind the compact web_* tools, modeled on AskAI's
 * BrowserSession. Every answer is short numbered plain text, which small models
 * follow more reliably than nested JSON. Navigation is a real browser navigation
 * with the current page as referer; no script is injected into the page.
 *
 * <p>Search results (result-N) and page links (link-N) are separate snapshots. A bare
 * number such as "2" refers to the list the agent saw last: web_search shows results,
 * web_links shows links. Anything that cannot be resolved fails with a clear message.
 *
 * <p>web_find searches the whole text of the page for a phrase and answers with the match count and short
 * snippets, each naming its web_read part, so a long page can be searched without reading every part.
 *
 * <p>The session follows the browser: a page another tool opened (expert profile: browser_navigate,
 * a click, a tab switch; any profile: the page web_research loaded last) is read by web_read and
 * web_links as well. Only the search provider's own page after web_search is not a page to read;
 * a result has to be opened first.
 */
public final class WebSession {
    public static final int DEFAULT_PART_CHARACTERS = 12_000;
    public static final int DEFAULT_MAX_LINKS = 150;
    /** web_find shows at most this many snippets of at most this many characters. */
    static final int MAX_FIND_SNIPPETS = 10;
    static final int FIND_SNIPPET_CHARACTERS = 200;
    /** Dashes a page may use where a model types '-' (e.g. U+2011 non-breaking hyphen). */
    private static final Pattern DASHES = Pattern.compile("[\\u2010-\\u2015\\u2212]");
    private static final Pattern TARGET = Pattern.compile(
            "^\\[?\\s*(link|result)?\\s*[-_ #]?\\s*(\\d{1,4})\\s*]?$");
    private static final String NO_PAGE = "No page is open yet. Call web_open or web_search first.";
    private static final String AFTER_SEARCH = "No page is open since the last web_search. "
            + "Open a result first, e.g. web_follow link_id=\"result-1\".";
    private static final String NO_WEB_PAGE = "The browser shows no http(s) page (%s). Call web_open or web_search first.";

    private enum Listing { RESULTS, LINKS }

    private final BrowserPagePort browser;
    private final UrlPolicy policy;
    private final WebSearchPort search;
    private final int partCharacters;
    private final int maxLinks;
    private final LinkExtractor extractor = new LinkExtractor();
    private final LinkRegistry links;
    private List<SearchHit> results = List.of();
    private Listing lastListing = Listing.LINKS;
    private PageCapture page;
    /** Where the browser stood after the last web_search: the provider's page, which is not adopted. */
    private String searchPage = "";

    public WebSession(BrowserPagePort browser, UrlPolicy policy, WebSearchPort search) {
        this(browser, policy, search, DEFAULT_PART_CHARACTERS, DEFAULT_MAX_LINKS);
    }

    public WebSession(BrowserPagePort browser, UrlPolicy policy, WebSearchPort search,
                      int partCharacters, int maxLinks) {
        this.browser = browser;
        this.policy = policy;
        this.search = search == null ? new UnavailableWebSearch() : search;
        this.partCharacters = partCharacters;
        this.maxLinks = maxLinks;
        this.links = new LinkRegistry(maxLinks);
    }

    /** The search backend, shared with the optional research orchestration. */
    public WebSearchPort searchPort() {
        return search;
    }

    public synchronized String search(String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search query.");
        SearchResults found;
        try {
            found = search.search(query.trim());
        } catch (Exception failed) {
            leaveProviderPage();
            throw failed;
        }
        Map<String, SearchHit> byUrl = new LinkedHashMap<>();
        for (SearchHit hit : found.hits()) {
            if (byUrl.size() < maxLinks) byUrl.putIfAbsent(hit.url(), hit);
        }
        results = List.copyOf(byUrl.values());
        lastListing = Listing.RESULTS;
        // The search provider navigated the browser; the previous page and its link ids are gone.
        page = null;
        links.clear();
        searchPage = browser.currentUrl();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchHit hit = results.get(i);
            out.append("result-").append(i + 1).append(": ").append(hit.title())
                    .append(" — ").append(hit.url()).append('\n');
            if (hit.snippet() != null && !hit.snippet().isBlank()) {
                out.append("   ").append(hit.snippet().strip()).append('\n');
            }
        }
        if (results.isEmpty()) out.append("No results.\n");
        else out.append("Open a result with web_follow link_id=\"result-1\".\n");
        found.notes().forEach(note -> out.append(note).append('\n'));
        return out.toString().stripTrailing();
    }

    public synchronized String open(String url) {
        policy.check(url);
        String referer = page == null ? null : page.url();
        return show(accept(browser.open(url.trim(), referer)), 1);
    }

    public synchronized String read(int part) {
        requireShownPage();
        return show(accept(browser.current()), part);
    }

    /**
     * Finds {@code query} in the text of the page the browser shows: the same text web_read hands out in
     * parts, read once like web_read. Only the match count and short snippets go back, each with the
     * web_read part it is in, so a long page never has to enter the context as a whole. Case, runs of
     * whitespace and dash variants do not matter.
     */
    public synchronized String find(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search text.");
        requireShownPage();
        PageCapture capture = accept(browser.current());
        String text = capture.text().strip();
        String searched = DASHES.matcher(text).replaceAll("-");
        StringBuilder phrase = new StringBuilder();
        for (String word : DASHES.matcher(query.strip()).replaceAll("-").split("\\s+")) {
            if (!phrase.isEmpty()) phrase.append("\\s+");
            phrase.append(Pattern.quote(word));
        }
        Matcher match = Pattern.compile(phrase.toString(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS).matcher(searched);
        int parts = Math.max(1, (text.length() + partCharacters - 1) / partCharacters);
        int count = 0;
        int shownUntil = -1;
        List<String> snippets = new ArrayList<>();
        while (match.find()) {
            count++;
            // A match inside the previous snippet is counted but not shown twice.
            if (match.start() < shownUntil || snippets.size() == MAX_FIND_SNIPPETS) continue;
            int[] window = snippetWindow(text, match.start(), match.end());
            shownUntil = window[1];
            String snippet = text.substring(window[0], window[1]).replaceAll("\\s+", " ").strip();
            snippets.add("hit " + (snippets.size() + 1) + " (part " + (match.start() / partCharacters + 1) + "): "
                    + (window[0] > 0 ? "…" : "") + snippet + (window[1] < text.length() ? "…" : ""));
        }
        String quoted = "\"" + query.strip() + "\"";
        StringBuilder out = new StringBuilder().append("URL: ").append(capture.url()).append('\n');
        if (count == 0) {
            return out.append("No matches for ").append(quoted).append(" on this page. Try a shorter or different word.")
                    .toString();
        }
        out.append("FOUND: ").append(count).append(count == 1 ? " match" : " matches").append(" for ").append(quoted);
        if (count > snippets.size()) out.append(" (showing ").append(snippets.size()).append(')');
        out.append('\n');
        snippets.forEach(snippet -> out.append(snippet).append('\n'));
        if (parts > 1) out.append("Read a whole part with web_read part=N.\n");
        return out.toString().stripTrailing();
    }

    /** At most {@link #FIND_SNIPPET_CHARACTERS} around the match, cut at word boundaries where possible. */
    private static int[] snippetWindow(String text, int start, int end) {
        int context = Math.max(0, (FIND_SNIPPET_CHARACTERS - (end - start)) / 2);
        int from = Math.max(0, start - context);
        int to = Math.min(text.length(), end + context);
        for (int i = from; from > 0 && i < start; i++) {
            if (Character.isWhitespace(text.charAt(i))) { from = i + 1; break; }
        }
        for (int i = to; to < text.length() && i > end; i--) {
            if (Character.isWhitespace(text.charAt(i))) { to = i; break; }
        }
        return new int[] {from, to};
    }

    /**
     * Links of the last capture; no extra browser read (web_read refreshes late-loaded links) unless
     * another tool moved the browser since, because the ids belong to the page it shows.
     */
    public synchronized String links() {
        String shown = requireShownPage();
        if (page == null || !WebAddress.withoutFragment(page.url()).equals(WebAddress.withoutFragment(shown))) {
            accept(browser.current());
        }
        lastListing = Listing.LINKS;
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

    /** Accepts "result-2", "link-3", "Link 3", "[3]" and bare "3" (resolved against the last list). */
    public synchronized String follow(String target) {
        Matcher match = TARGET.matcher(target == null ? "" : target.trim().toLowerCase(Locale.ROOT));
        if (!match.matches()) {
            throw new IllegalArgumentException("Unknown link id: " + target
                    + ". Use an id such as link-3 from web_links or result-2 from web_search.");
        }
        int number = Integer.parseInt(match.group(2));
        boolean result = match.group(1) == null ? lastListing == Listing.RESULTS : match.group(1).equals("result");
        if (result) {
            if (number < 1 || number > results.size()) {
                throw new IllegalArgumentException("Unknown result id: result-" + number + ". The last web_search "
                        + (results.isEmpty() ? "returned no results." : "returned result-1 to result-" + results.size() + "."));
            }
            return open(results.get(number - 1).url());
        }
        PageLink link = page == null ? null : links.find(number);
        if (link == null) {
            throw new IllegalArgumentException("Unknown link id: link-" + number
                    + ". Link ids belong to the current page; call web_links first.");
        }
        return open(link.url());
    }

    public synchronized String back() {
        requireShownPage();
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
            browser.clear();
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

    /**
     * A failed search (consent wall, challenge, no engine) may have left the browser on the provider's page;
     * then the previous page is gone like after a successful search. If the browser did not move, the page
     * the agent had stays readable.
     */
    private void leaveProviderPage() {
        String shown = browser.currentUrl();
        if (page != null && page.url().equals(shown)) return;
        page = null;
        links.clear();
        searchPage = shown;
    }

    /**
     * The address the browser shows, which web_* read even when another tool opened it (expert profile:
     * browser_navigate, clicks, tab switches). A blank tab or a non-http(s) document (blob:, srcdoc) is
     * nothing to read and is left untouched; the search provider's page after web_search is left to
     * web_follow, also when the search found nothing.
     */
    private String requireShownPage() {
        String shown = browser.currentUrl();
        if (shown.isBlank() || "about:blank".equals(shown)) {
            forgetPage();
            throw new IllegalStateException(results.isEmpty() ? NO_PAGE : AFTER_SEARCH);
        }
        if (!isWeb(shown)) {
            forgetPage();
            throw new IllegalStateException(String.format(NO_WEB_PAGE, shown));
        }
        if (page == null && sameDocument(shown, searchPage)) {
            throw new IllegalStateException(results.isEmpty() ? NO_PAGE : AFTER_SEARCH);
        }
        return shown;
    }

    /** Same scheme, host and path: a results page that rewrites its query or fragment later is still the results page. */
    private static boolean sameDocument(String a, String b) {
        return !b.isEmpty() && a.split("[?#]", 2)[0].equalsIgnoreCase(b.split("[?#]", 2)[0]);
    }

    private void forgetPage() {
        page = null;
        links.clear();
    }

    private static boolean isWeb(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
