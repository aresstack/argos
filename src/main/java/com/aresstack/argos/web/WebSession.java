package com.aresstack.argos.web;

import com.aresstack.argos.fetch.WebAddress;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    static final int MAX_ECHOED_PHRASE = 80;
    /** Dashes a page may use where a model types '-' (e.g. U+2011 non-breaking hyphen). */
    private static final Pattern DASHES = Pattern.compile("[\\u2010-\\u2015\\u2212]");
    private static final Pattern TARGET = Pattern.compile(
            "^\\[?\\s*(link|result)?\\s*[-_ #]?\\s*(\\d{1,4})\\s*]?$");
    private static final String NO_PAGE = "No page is open yet. Call web_open or web_search first.";
    private static final String AFTER_SEARCH = "No page is open since the last web_search. "
            + "Open a result first, e.g. web_follow link_id=\"result-1\".";
    private static final String NO_WEB_PAGE = "The browser shows no http(s) page (%s). Call web_open or web_search first.";
    /** Follows the status of an error page (Angelo's wording); the agent should look for the content elsewhere. */
    static final String ERROR_STATUS_NOTE = " \u2014 the requested content may not be available on this page.";

    private enum Listing { RESULTS, LINKS }

    /**
     * Notified about every page the web_* tools read, after the URL policy accepted it and while the browser
     * still shows it (the Search History records and archives it there). {@code newVisit} is false when the shown
     * page is only read again (web_read, web_find, web_links). A failing listener never fails the tool.
     */
    @FunctionalInterface
    public interface PageListener {
        void pageRead(PageCapture capture, String requestedUrl, boolean newVisit, String tool);
    }

    private final BrowserPagePort browser;
    private final UrlPolicy policy;
    private final WebSearchPort search;
    private final int partCharacters;
    private final int maxLinks;
    private final LinkExtractor extractor = new LinkExtractor();
    private final LinkRegistry links;
    private List<SearchHit> results = List.of();
    /** Search results already opened during the current search; avoid recommending them again. */
    private final Set<String> attemptedResultUrls = new HashSet<>();
    private String lastSearchQuery = "";
    private Listing lastListing = Listing.LINKS;
    private PageCapture page;
    /** Where the browser stood after the last web_search: the provider's page, which is not adopted. */
    private String searchPage = "";
    private volatile PageListener pageListener;

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

    /** Registers the listener for read pages (at most one; null removes it). */
    public void onPageRead(PageListener listener) {
        this.pageListener = listener;
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
        attemptedResultUrls.clear();
        lastSearchQuery = query.trim();
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
        String requestedUrl = url.trim();
        markResultAttempted(requestedUrl);
        String referer = page == null ? null : page.url();
        PageCapture capture = accept(browser.open(requestedUrl, referer));
        notifyRead(capture, requestedUrl, true, "web_open");
        return show(capture, 1, requestedUrl);
    }

    public synchronized String read(int part) {
        requireShownPage();
        PageCapture capture = accept(browser.current());
        notifyRead(capture, null, false, "web_read");
        return show(capture, part, capture.url());
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
        notifyRead(capture, null, false, "web_find");
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
        String phraseShown = query.strip();
        // The answer echoes the phrase, but never a whole pasted paragraph.
        if (phraseShown.length() > MAX_ECHOED_PHRASE) phraseShown = phraseShown.substring(0, MAX_ECHOED_PHRASE) + "…";
        String quoted = "\"" + phraseShown + "\"";
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

    /**
     * At most {@link #FIND_SNIPPET_CHARACTERS} around the match, cut at word boundaries where possible. A match
     * longer than that (a very long search phrase) is shown from its start, cut after the limit like any other
     * snippet; the "…" markers are not counted.
     */
    private static int[] snippetWindow(String text, int start, int end) {
        if (end - start >= FIND_SNIPPET_CHARACTERS) {
            int to = start + FIND_SNIPPET_CHARACTERS;
            for (int i = to; i > start; i--) {
                if (Character.isWhitespace(text.charAt(i))) { to = i; break; }
            }
            return new int[] {start, to};
        }
        int context = (FIND_SNIPPET_CHARACTERS - (end - start)) / 2;
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
            notifyRead(accept(browser.current()), null, false, "web_links");
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
        PageCapture capture = accept(previous);
        notifyRead(capture, null, true, "web_back");
        return show(capture, 1, capture.url());
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

    private void notifyRead(PageCapture capture, String requestedUrl, boolean newVisit, String tool) {
        PageListener listener = pageListener;
        if (listener == null) return;
        try {
            listener.pageRead(capture, requestedUrl, newVisit, tool);
        } catch (RuntimeException ignored) {
            // Recording is a side effect; the page the agent asked for is delivered regardless.
        }
    }

    /** The line that marks an error page: {@code HTTP error: 404 Not Found — the requested content ...}. */
    static String errorLine(int status) {
        String reason = reasonPhrase(status);
        return "HTTP error: " + status + (reason.isEmpty() ? "" : " " + reason) + ERROR_STATUS_NOTE;
    }

    /** The standard reason phrase (RFC 9110) for the codes agents meet; "" for the rest. */
    private static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 429 -> "Too Many Requests";
            case 451 -> "Unavailable For Legal Reasons";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    private String show(PageCapture capture, int part, String requestedUrl) {
        String text = capture.text().strip();
        int parts = Math.max(1, (text.length() + partCharacters - 1) / partCharacters);
        if (part < 1 || part > parts) {
            throw new IllegalArgumentException("This page has " + parts + " part(s); use part 1 to " + parts + ".");
        }
        int start = (part - 1) * partCharacters;
        String slice = text.substring(start, Math.min(text.length(), start + partCharacters));
        StringBuilder out = new StringBuilder()
                .append("URL: ").append(capture.url()).append('\n')
                .append("TITLE: ").append(capture.title()).append('\n');
        if (capture.isError()) {
            out.append(errorLine(capture.status())).append('\n');
            appendRecoveryInstruction(out, capture, requestedUrl);
        }
        out.append("LINKS: ").append(links.links().size()).append(" (see web_links)").append('\n');
        if (parts > 1) {
            out.append("PART: ").append(part).append(" of ").append(parts);
            if (part < parts) out.append(" (next: web_read part=").append(part + 1).append(')');
            out.append('\n');
        }
        return out.append('\n').append(slice).toString();
    }

    /**
     * Give the model one concrete next action derived from the current search state.
     * Do not make it infer a recovery strategy from the status code alone.
     */
    private void appendRecoveryInstruction(StringBuilder out, PageCapture capture, String requestedUrl) {
        String failedUrl = requestedUrl == null || requestedUrl.isBlank() ? capture.url() : requestedUrl;
        markResultAttempted(failedUrl);
        int nextResult = nextUntriedResult(failedUrl);
        if (nextResult >= 0) {
            int resultNumber = nextResult + 1;
            out.append("NEXT ACTION: Do not retry this failed URL. Open the next untried search result now: result-")
                    .append(resultNumber).append(". Call web_follow link_id=\"result-")
                    .append(resultNumber).append("\".").append('\n');
            return;
        }
        if (!lastSearchQuery.isBlank()) {
            out.append("NEXT ACTION: No untried result remains from the last search. Call web_search query=\"")
                    .append(escapeToolArgument(lastSearchQuery + " alternative source"))
                    .append("\" to find an alternative source. Do not reopen the failed URL.").append('\n');
            return;
        }
        out.append("NEXT ACTION: Do not retry this failed URL immediately. Call web_search with a query for the same information from an alternative source.")
                .append('\n');
    }

    private void markResultAttempted(String url) {
        String key = resultKey(url);
        if (key.isEmpty()) return;
        for (SearchHit result : results) {
            if (resultKey(result.url()).equals(key)) {
                attemptedResultUrls.add(key);
                return;
            }
        }
    }

    private int nextUntriedResult(String failedUrl) {
        String failedKey = resultKey(failedUrl);
        for (int i = 0; i < results.size(); i++) {
            String candidateKey = resultKey(results.get(i).url());
            if (candidateKey.isEmpty() || candidateKey.equals(failedKey)
                    || attemptedResultUrls.contains(candidateKey)) continue;
            return i;
        }
        return -1;
    }

    private static String resultKey(String url) {
        return url == null || url.isBlank() ? "" : WebAddress.withoutFragment(url.trim());
    }

    private static String escapeToolArgument(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
