package com.aresstack.argos.web;

import com.aresstack.argos.fetch.WebAddress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The research session behind the web_* tools, modeled on AskAI's BrowserSession. Every answer is short
 * numbered plain text, which small models follow more reliably than nested JSON. Navigation is a real browser
 * navigation with the current page as referer; no script is injected into the page.
 *
 * <p>Every answer that shows a page (open, follow, back, read) carries the page's links with their ids in the
 * same answer, so the agent never has to ask what it can open next. Search results (result-N) and page links
 * (link-N) are separate references. The agent never sees their URLs: web_open_result and web_follow navigate
 * with the exact stored URL (no normalization, every query parameter kept), and web_get_url hands that URL out
 * only on request. Ids are never reused within a session, so an id of an earlier search, an earlier page or a
 * closed tab is rejected as stale. A bare number such as "2" refers to the list the agent saw last.
 *
 * <p>With research tabs (the convenience profile) every opened search result gets its own tab, several results
 * load in parallel with per-host pacing ({@link HostThrottle}), a link can be followed in a new tab, and a new
 * search runs in a tab of its own so open research tabs stay. Link ids name their tab by themselves. Without
 * research tabs everything happens in the tab the browser shows, as before.
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
    /** Research tabs: open tabs with a page at most, results per web_open_result, navigations in flight. */
    static final int MAX_RESEARCH_TABS = 8;
    static final int MAX_RESULTS_PER_CALL = 5;
    static final int MAX_PARALLEL_LOADS = 4;
    /** Text of each page when several open in one answer; web_read gives the whole part. */
    static final int MIN_SHARED_CHARACTERS = 2_000;
    /** Dashes a page may use where a model types '-' (e.g. U+2011 non-breaking hyphen). */
    private static final Pattern DASHES = Pattern.compile("[\\u2010-\\u2015\\u2212]");
    private static final Pattern TARGET = Pattern.compile(
            "^\\[?\\s*(link|result)?\\s*[-_ #]?\\s*(\\d{1,5})\\s*]?$");
    private static final Pattern TAB_ID = Pattern.compile("^t?\\d{1,4}$");
    private static final String NO_PAGE = "No page is open yet. Call web_open or web_search first.";
    private static final String AFTER_SEARCH = "No page is open since the last web_search. "
            + "Open a result first, e.g. web_open_result result_id=\"result-1\".";
    private static final String NO_WEB_PAGE = "The browser shows no http(s) page (%s). Call web_open or web_search first.";
    /** Follows the status of an error page (Angelo's wording); the agent should look for the content elsewhere. */
    static final String ERROR_STATUS_NOTE = " — the requested content may not be available on this page.";

    private enum Listing { RESULTS, LINKS }

    /** A resolved id: a search result (tab null) or a link of a tab's page, with its exact stored URL. */
    private record Reference(Listing kind, String id, String url, String tab) {
    }

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
    private final HostThrottle throttle;
    private List<SearchHit> results = List.of();
    /** Number of result-N for results.get(0); ids continue across searches and are never reused. */
    private int firstResult = 1;
    /** Search results already opened during the current search; avoid recommending them again. */
    private final Set<String> attemptedResultUrls = new HashSet<>();
    private String lastSearchQuery = "";
    private Listing lastListing = Listing.LINKS;
    /** The page each tab shows as the web_* tools read it last; a tab without entry has no page to read. */
    private final Map<String, PageCapture> pages = new HashMap<>();
    /** How a tab's research path started (result-2, link-7 of t1, a given URL) and how many pages it read. */
    private final Map<String, String> origins = new HashMap<>();
    private final Map<String, Integer> visits = new HashMap<>();
    /** Finished research paths, for web_tabs. */
    private final List<String> closedTabs = new ArrayList<>();
    /** The tab and address the last web_search left on the provider's page, which is not adopted. */
    private String searchTab = "";
    private String searchPage = "";
    private volatile boolean researchTabs;
    private volatile PageListener pageListener;

    public WebSession(BrowserPagePort browser, UrlPolicy policy, WebSearchPort search) {
        this(browser, policy, search, DEFAULT_PART_CHARACTERS, DEFAULT_MAX_LINKS);
    }

    public WebSession(BrowserPagePort browser, UrlPolicy policy, WebSearchPort search,
                      int partCharacters, int maxLinks) {
        this(browser, policy, search, partCharacters, maxLinks, new HostThrottle(HostThrottle.SYSTEM,
                HostThrottle.DEFAULT_SPACING_MILLIS, HostThrottle.DEFAULT_COOLDOWN_MILLIS));
    }

    WebSession(BrowserPagePort browser, UrlPolicy policy, WebSearchPort search,
               int partCharacters, int maxLinks, HostThrottle throttle) {
        this.browser = browser;
        this.policy = policy;
        this.search = search == null ? new UnavailableWebSearch() : search;
        this.partCharacters = partCharacters;
        this.maxLinks = maxLinks;
        this.links = new LinkRegistry(maxLinks);
        this.throttle = throttle;
    }

    /** Registers the listener for read pages (at most one; null removes it). */
    public void onPageRead(PageListener listener) {
        this.pageListener = listener;
    }

    /**
     * Research tabs (convenience profile): results open in their own tabs, in parallel where the browser has
     * tabs. Without them (or with a single-tab browser) every page opens in the tab the browser shows.
     */
    public void useResearchTabs(boolean enabled) {
        this.researchTabs = enabled;
    }

    private boolean tabbed() {
        return researchTabs && browser.supportsTabs();
    }

    /** The search backend, shared with the optional research orchestration. */
    public WebSearchPort searchPort() {
        return search;
    }

    public synchronized String search(String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search query.");
        String tabNote = tabbed() ? searchTabForResearch() : "";
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
        firstResult += results.size();
        results = List.copyOf(byUrl.values());
        attemptedResultUrls.clear();
        lastSearchQuery = query.trim();
        lastListing = Listing.RESULTS;
        // The search provider navigated this tab; its previous page and link ids are gone.
        String tab = browser.currentTab();
        forget(tab);
        searchTab = tab;
        searchPage = browser.currentUrl(tab);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchHit hit = results.get(i);
            out.append("result-").append(firstResult + i).append(": ").append(hit.title()).append('\n');
            if (hit.snippet() != null && !hit.snippet().isBlank()) {
                out.append("   ").append(hit.snippet().strip()).append('\n');
            }
        }
        if (results.isEmpty()) out.append("No results.\n");
        else out.append("Open results with web_open_result result_id=\"result-").append(firstResult).append("\".\n");
        found.notes().forEach(note -> out.append(note).append('\n'));
        out.append(tabNote);
        return out.toString().stripTrailing();
    }

    /**
     * Keeps open research tabs: when the browser shows one, the search runs in the previous search tab or a new
     * one. @return the note for the answer, "" when nothing moved
     */
    private String searchTabForResearch() {
        String current = browser.currentTab();
        if (!pages.containsKey(current)) return "";
        String tab = !searchTab.isEmpty() && !pages.containsKey(searchTab) && browser.hasTab(searchTab)
                ? searchTab : browser.newTab();
        browser.showTab(tab);
        return "Search ran in tab " + tab + "; research tabs stay open: " + String.join(", ", openTabs()) + ".\n";
    }

    /** Opens a URL the user gave in the tab the browser shows. */
    public synchronized String open(String url) {
        String tab = browser.currentTab();
        return openIn(tab, url, "web_open").answer();
    }

    private record Opened(String tab, PageCapture capture, String answer) {
    }

    /**
     * One navigation of {@code tab} (blank = the shown tab) with pacing and the policy checks. The Search History
     * records every opened page as web_open, whichever id led there.
     */
    private Opened openIn(String tab, String url, String origin) {
        policy.check(url);
        String requestedUrl = url.trim();
        String host = HostThrottle.host(requestedUrl);
        requireNoCooldown(host);
        markResultAttempted(requestedUrl);
        PageCapture before = pages.get(tab);
        String referer = before == null ? null : before.url();
        throttle.awaitTurn(List.of(host));
        PageCapture raw = browser.open(requestedUrl, referer, tab.isEmpty() ? null : tab);
        if (tab.isEmpty()) tab = browser.currentTab();
        PageCapture capture = accept(tab, raw);
        throttle.answered(host, capture.status());
        if (before == null || !origins.containsKey(tab)) origins.put(tab, origin);
        visits.merge(tab, 1, Integer::sum);
        notifyRead(capture, requestedUrl, true, "web_open");
        return new Opened(tab, capture, show(tab, capture, 1, requestedUrl, partCharacters));
    }

    private void requireNoCooldown(String host) {
        long wait = throttle.cooldown(host);
        if (wait > 0) {
            throw new IllegalStateException(host + " refused recent requests (HTTP 429/403). Wait about "
                    + Math.max(1, wait / 1000) + " s before opening it again, or open another result.");
        }
    }

    public synchronized String read(int part) {
        return read(part, null);
    }

    public synchronized String read(int part, String tabId) {
        String tab = tab(tabId);
        requireShownPage(tab);
        PageCapture capture = accept(tab, browser.current(tab));
        notifyRead(capture, null, false, "web_read");
        return show(tab, capture, part, capture.url(), partCharacters);
    }

    public synchronized String find(String query) {
        return find(query, null);
    }

    /**
     * Finds {@code query} in the text of the page the tab shows: the same text web_read hands out in
     * parts, read once like web_read. Only the match count and short snippets go back, each with the
     * web_read part it is in, so a long page never has to enter the context as a whole. Case, runs of
     * whitespace and dash variants do not matter.
     */
    public synchronized String find(String query, String tabId) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search text.");
        String tab = tab(tabId);
        requireShownPage(tab);
        PageCapture capture = accept(tab, browser.current(tab));
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
        StringBuilder out = new StringBuilder();
        if (tabbed()) out.append("TAB: ").append(tab).append('\n');
        out.append("TITLE: ").append(capture.title()).append('\n');
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

    public synchronized String links() {
        return links(null);
    }

    /**
     * The links of a tab's page again (every page answer lists them already); no extra browser read unless
     * another tool moved the tab since, because the ids belong to the page it shows.
     */
    public synchronized String links(String tabId) {
        String tab = tab(tabId);
        String shown = requireShownPage(tab);
        PageCapture page = pages.get(tab);
        if (page == null || !WebAddress.withoutFragment(page.url()).equals(WebAddress.withoutFragment(shown))) {
            notifyRead(accept(tab, browser.current(tab)), null, false, "web_links");
        }
        lastListing = Listing.LINKS;
        if (links.links(tab).isEmpty()) return "No links on this page.";
        return appendLinks(new StringBuilder(), tab).toString().stripTrailing();
    }

    /** id: text per line, never the URL; the truncation marker when the page had more links than the limit. */
    private StringBuilder appendLinks(StringBuilder out, String tab) {
        for (PageLink link : links.links(tab)) {
            out.append(link.id()).append(": ").append(link.text().isEmpty() ? "(no text)" : link.text()).append('\n');
        }
        if (links.truncated(tab)) out.append("(list truncated: more links not shown)\n");
        return out;
    }

    /** Accepts "result-2", "link-3", "Link 3", "[3]" and bare "3" (resolved against the last list). */
    public synchronized String follow(String target) {
        return follow(target, false);
    }

    /**
     * Opens a link with its exact stored URL in the tab whose page has it, or in a new tab so that page stays
     * ({@code newTab}, research tabs only). A result id opens like web_open_result.
     */
    public synchronized String follow(String target, boolean newTab) {
        Reference reference = resolve(target, null);
        if (reference.kind() == Listing.RESULTS) return openResults(List.of(reference));
        String from = reference.tab();
        String tab = from;
        if (newTab && tabbed()) {
            requireTabRoom(1);
            tab = browser.newTab();
        }
        String origin = tab.equals(from) ? null : reference.id() + " of " + from;
        if (origin != null) {
            // The new tab starts from the page the link was on: same referer as a click there.
            pages.put(tab, pages.get(from));
        }
        Opened opened;
        try {
            opened = openIn(tab, reference.url(), origin == null ? reference.id() : origin);
        } finally {
            if (origin != null && pages.get(tab) == pages.get(from)) pages.remove(tab);
        }
        if (tabbed()) browser.showTab(opened.tab());
        return opened.answer();
    }

    /** Opens search results by id ("result-2", "2", several separated by commas or spaces). */
    public synchronized String openResult(String resultIds) {
        List<String> ids = new ArrayList<>();
        for (String id : (resultIds == null ? "" : resultIds).split("[,;]+|\\s+(?=(?i:result))")) {
            if (!id.isBlank()) ids.add(id.strip());
        }
        return openResult(ids);
    }

    public synchronized String openResult(List<String> resultIds) {
        if (resultIds == null || resultIds.isEmpty()) throw new IllegalArgumentException("Give a result_id such as result-1.");
        Map<String, Reference> chosen = new LinkedHashMap<>();
        for (String id : resultIds) {
            Reference reference = resolve(id, Listing.RESULTS);
            chosen.putIfAbsent(reference.id(), reference);
        }
        return openResults(new ArrayList<>(chosen.values()));
    }

    private String openResults(List<Reference> chosen) {
        if (!tabbed()) {
            if (chosen.size() > 1) {
                throw new IllegalArgumentException("Open one result at a time in this browser, e.g. result_id=\""
                        + chosen.get(0).id() + "\".");
            }
            Reference only = chosen.get(0);
            return openIn(browser.currentTab(), only.url(), only.id()).answer();
        }
        if (chosen.size() > MAX_RESULTS_PER_CALL) {
            throw new IllegalArgumentException("Open at most " + MAX_RESULTS_PER_CALL + " results per call.");
        }
        Map<String, String> notes = new LinkedHashMap<>();
        List<Reference> pending = new ArrayList<>();
        for (Reference reference : chosen) {
            String openIn = tabShowing(reference.url());
            if (openIn != null) {
                notes.put(reference.id(), "already open in tab " + openIn + " (web_read tab=\"" + openIn + "\").");
                continue;
            }
            policy.check(reference.url());
            pending.add(reference);
        }
        int room = MAX_RESEARCH_TABS - openTabs().size();
        while (pending.size() > Math.max(0, room)) {
            Reference skipped = pending.remove(pending.size() - 1);
            notes.put(skipped.id(), "not opened: " + MAX_RESEARCH_TABS
                    + " research tabs are open. Close finished ones with web_close_tab.");
        }
        List<Opened> opened = new ArrayList<>();
        Map<String, Reference> openedFrom = new HashMap<>();
        while (!pending.isEmpty()) {
            // One navigation per host in each wave: different sites load in parallel, one site never twice at once.
            List<Reference> wave = new ArrayList<>();
            Set<String> hosts = new LinkedHashSet<>();
            for (Reference reference : List.copyOf(pending)) {
                String host = HostThrottle.host(reference.url());
                long wait = throttle.cooldown(host);
                if (wait > 0) {
                    pending.remove(reference);
                    notes.put(reference.id(), "not opened: " + host + " refused recent requests (HTTP 429/403). "
                            + "Wait about " + Math.max(1, wait / 1000) + " s or open another result.");
                    continue;
                }
                if (hosts.add(host)) {
                    wave.add(reference);
                    pending.remove(reference);
                }
            }
            if (wave.isEmpty()) continue;
            throttle.awaitTurn(hosts);
            List<String> urls = wave.stream().map(Reference::url).toList();
            List<BrowserPagePort.TabPage> loaded = browser.openInNewTabs(urls, Math.min(MAX_PARALLEL_LOADS, urls.size()));
            for (int i = 0; i < wave.size(); i++) {
                Reference reference = wave.get(i);
                BrowserPagePort.TabPage page = i < loaded.size() ? loaded.get(i) : null;
                markResultAttempted(reference.url());
                if (page == null || page.capture() == null) {
                    String error = page == null ? "no answer from the browser" : page.error();
                    if (page != null && !page.tab().isEmpty()) closeQuietly(page.tab());
                    notes.put(reference.id(), "failed: " + error);
                    continue;
                }
                PageCapture capture;
                try {
                    capture = accept(page.tab(), page.capture());
                } catch (IllegalArgumentException blocked) {
                    closeQuietly(page.tab());
                    notes.put(reference.id(), "failed: " + blocked.getMessage());
                    continue;
                }
                throttle.answered(HostThrottle.host(reference.url()), capture.status());
                origins.put(page.tab(), reference.id());
                visits.merge(page.tab(), 1, Integer::sum);
                notifyRead(capture, reference.url(), true, "web_open");
                opened.add(new Opened(page.tab(), capture, ""));
                openedFrom.put(page.tab(), reference);
            }
        }
        if (!opened.isEmpty()) browser.showTab(opened.get(0).tab());
        if (opened.size() == 1 && notes.isEmpty()) {
            Opened only = opened.get(0);
            return show(only.tab(), only.capture(), 1, openedFrom.get(only.tab()).url(), partCharacters);
        }
        StringBuilder out = new StringBuilder();
        if (!opened.isEmpty()) {
            out.append("Opened ").append(opened.size()).append(opened.size() == 1 ? " result in tab " : " results in tabs ")
                    .append(String.join(", ", opened.stream().map(Opened::tab).toList())).append(".\n");
        }
        notes.forEach((id, note) -> out.append(id).append(": ").append(note).append('\n'));
        int share = Math.max(MIN_SHARED_CHARACTERS, partCharacters / Math.max(1, opened.size()));
        for (Opened page : opened) {
            out.append("\n=== ").append(openedFrom.get(page.tab()).id()).append(" in tab ").append(page.tab())
                    .append(" ===\n")
                    .append(show(page.tab(), page.capture(), 1, openedFrom.get(page.tab()).url(), share)).append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** The exact stored URL of a result id, a link id, or the page a tab shows; never navigates. */
    public synchronized String url(String referenceId) {
        String id = referenceId == null ? "" : referenceId.trim().toLowerCase(Locale.ROOT);
        if (TAB_ID.matcher(id).matches() && id.startsWith("t")) {
            PageCapture page = pages.get(id);
            if (page == null) throw new IllegalArgumentException("Tab " + id + " shows no page read by the web tools.");
            return page.url();
        }
        return resolve(referenceId, null).url();
    }

    /** Research paths: each tab with its page title, how it started, pages read, and the closed ones. */
    public synchronized String tabs() {
        String current = browser.currentTab();
        StringBuilder out = new StringBuilder();
        for (String tab : openTabs()) {
            PageCapture page = pages.get(tab);
            out.append(tab).append(tab.equals(current) ? " (current)" : "").append(": ")
                    .append(page.title().isBlank() ? "(no title)" : page.title())
                    .append(" — from ").append(origins.getOrDefault(tab, "another tool"))
                    .append(", ").append(visits.getOrDefault(tab, 0)).append(" page(s) read")
                    .append(page.isError() ? ", HTTP error " + page.status() : "").append('\n');
        }
        if (!searchTab.isEmpty() && browser.hasTab(searchTab) && !pages.containsKey(searchTab)) {
            out.append(searchTab).append(searchTab.equals(current) ? " (current)" : "")
                    .append(": search \"").append(lastSearchQuery).append("\"\n");
        }
        if (out.isEmpty()) out.append("No research tabs are open.\n");
        if (!closedTabs.isEmpty()) out.append("Closed: ").append(String.join("; ", closedTabs)).append('\n');
        return out.toString().stripTrailing();
    }

    /** Ends a research path: the tab closes (the last tab is emptied instead) and its link ids become stale. */
    public synchronized String closeTab(String tabId) {
        String tab = normalizeTab(tabId);
        if (!pages.containsKey(tab) && !tab.equals(searchTab) && !browser.hasTab(tab)) {
            throw new IllegalArgumentException("Unknown tab " + tab + ". Open tabs: " + String.join(", ", openTabs()) + ".");
        }
        PageCapture page = pages.get(tab);
        closedTabs.add(tab + " (" + (page == null ? "search" : page.title().isBlank() ? "no title" : page.title())
                + ", from " + origins.getOrDefault(tab, page == null ? "web_search" : "another tool") + ")");
        forget(tab);
        origins.remove(tab);
        visits.remove(tab);
        if (tab.equals(searchTab)) searchTab = "";
        closeQuietly(tab);
        List<String> open = openTabs();
        return "Closed tab " + tab + ". " + (open.isEmpty() ? "No research tabs are open."
                : "Open research tabs: " + String.join(", ", open) + ".");
    }

    private void closeQuietly(String tab) {
        try {
            if (browser.supportsTabs()) {
                browser.discardTab(tab);
                return;
            }
        } catch (RuntimeException lastTab) {
            // The last tab stays open; it is emptied below instead.
        }
        try {
            browser.clear(tab);
        } catch (RuntimeException ignored) {
            // Nothing left to show either way.
        }
    }

    /** Tabs with a page the web tools read, in id order. */
    private List<String> openTabs() {
        List<String> open = new ArrayList<>();
        for (String tab : pages.keySet()) {
            if (browser.hasTab(tab)) open.add(tab);
        }
        open.sort((a, b) -> Integer.compare(tabNumber(a), tabNumber(b)));
        return open;
    }

    private static int tabNumber(String tab) {
        try {
            return Integer.parseInt(tab.replaceAll("\\D", ""));
        } catch (NumberFormatException none) {
            return Integer.MAX_VALUE;
        }
    }

    private void requireTabRoom(int more) {
        if (openTabs().size() + more > MAX_RESEARCH_TABS) {
            throw new IllegalStateException(MAX_RESEARCH_TABS + " research tabs are open. Close finished ones with web_close_tab.");
        }
    }

    /** The open tab that shows exactly {@code url} (no normalization) without an error status, or null. */
    private String tabShowing(String url) {
        for (String tab : openTabs()) {
            if (pages.get(tab).url().equals(url) && !pages.get(tab).isError()) return tab;
        }
        return null;
    }

    /** Resolves a result or link id; {@code only} restricts the kind (null = both, bare numbers = last list). */
    private Reference resolve(String target, Listing only) {
        Matcher match = TARGET.matcher(target == null ? "" : target.trim().toLowerCase(Locale.ROOT));
        if (!match.matches()) {
            throw new IllegalArgumentException("Unknown id: " + target
                    + ". Use an id such as result-2 from web_search or link-3 from a page answer.");
        }
        int number = Integer.parseInt(match.group(2));
        Listing kind = match.group(1) == null
                ? (only != null ? only : lastListing)
                : match.group(1).equals("result") ? Listing.RESULTS : Listing.LINKS;
        if (only != null && kind != only) {
            throw new IllegalArgumentException("link-" + number + " is a page link, not a search result. "
                    + "Open it with web_follow link_id=\"link-" + number + "\".");
        }
        if (kind == Listing.RESULTS) {
            int index = number - firstResult;
            if (index < 0 || index >= results.size()) {
                String known = results.isEmpty() ? "returned no results."
                        : "returned result-" + firstResult + " to result-" + (firstResult + results.size() - 1) + ".";
                throw new IllegalArgumentException((number >= 1 && number < firstResult
                        ? "Stale result id: result-" + number + " belongs to an earlier search. "
                        : "Unknown result id: result-" + number + ". ") + "The last web_search " + known);
            }
            return new Reference(Listing.RESULTS, "result-" + number, results.get(index).url(), null);
        }
        LinkRegistry.TabLink found = links.find(number);
        if (found == null || !pages.containsKey(found.tab()) || !browser.hasTab(found.tab())) {
            throw new IllegalArgumentException((links.stale(number) || found != null
                    ? "Stale link id: link-" + number + " belongs to an earlier page or a closed tab. "
                    : "Unknown link id: link-" + number + ". ")
                    + "Use a link id listed with a current page.");
        }
        return new Reference(Listing.LINKS, found.link().id(), found.link().url(), found.tab());
    }

    public synchronized String back() {
        return back(null);
    }

    public synchronized String back(String tabId) {
        String tab = tab(tabId);
        requireShownPage(tab);
        PageCapture previous = browser.back(tab);
        if (previous == null) throw new IllegalStateException("No previous page in history.");
        PageCapture capture = accept(tab, previous);
        visits.merge(tab, 1, Integer::sum);
        notifyRead(capture, null, true, "web_back");
        return show(tab, capture, 1, capture.url(), partCharacters);
    }

    /** The tab a call names (made current when the browser has tabs), or the tab the browser shows. */
    private String tab(String tabId) {
        if (tabId == null || tabId.isBlank()) return browser.currentTab();
        String tab = normalizeTab(tabId);
        if (!browser.hasTab(tab)) {
            throw new IllegalArgumentException("Unknown tab " + tab + ". Open research tabs: "
                    + (openTabs().isEmpty() ? "none" : String.join(", ", openTabs())) + ".");
        }
        if (browser.supportsTabs()) browser.showTab(tab);
        return tab;
    }

    private static String normalizeTab(String tabId) {
        String tab = tabId == null ? "" : tabId.trim().toLowerCase(Locale.ROOT);
        return tab.matches("\\d+") ? "t" + tab : tab;
    }

    /** Re-checks the final URL after redirects and refreshes the tab's link ids. */
    private PageCapture accept(String tab, PageCapture capture) {
        try {
            policy.check(capture.url());
        } catch (IllegalArgumentException blocked) {
            forget(tab);
            browser.clear(tab);
            throw new IllegalArgumentException("Blocked after redirect: " + blocked.getMessage());
        }
        pages.put(tab, capture);
        links.update(tab, capture.url(), extractor.extract(capture.html(), capture.url()));
        if (tab.equals(searchTab)) searchTab = "";
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

    private String show(String tab, PageCapture capture, int part, String requestedUrl, int maxCharacters) {
        String text = capture.text().strip();
        int parts = Math.max(1, (text.length() + partCharacters - 1) / partCharacters);
        if (part < 1 || part > parts) {
            throw new IllegalArgumentException("This page has " + parts + " part(s); use part 1 to " + parts + ".");
        }
        int start = (part - 1) * partCharacters;
        String slice = text.substring(start, Math.min(text.length(), start + partCharacters));
        boolean shortened = slice.length() > maxCharacters;
        if (shortened) slice = slice.substring(0, maxCharacters);
        StringBuilder out = new StringBuilder();
        if (tabbed()) out.append("TAB: ").append(tab).append('\n');
        out.append("TITLE: ").append(capture.title()).append('\n');
        if (capture.isError()) {
            out.append(errorLine(capture.status())).append('\n');
            appendRecoveryInstruction(out, capture, requestedUrl);
        }
        if (parts > 1) {
            out.append("PART: ").append(part).append(" of ").append(parts);
            if (part < parts) out.append(" (next: web_read part=").append(part + 1).append(')');
            out.append('\n');
        }
        out.append('\n').append(slice);
        if (shortened) {
            out.append("… (shortened; web_read tab=\"").append(tab).append("\" shows the whole part)");
        }
        out.append("\n\n");
        if (links.links(tab).isEmpty()) return out.append("LINKS: none").toString();
        out.append("LINKS (open with web_follow link_id):\n");
        return appendLinks(out, tab).toString().stripTrailing();
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
            int resultNumber = firstResult + nextResult;
            out.append("NEXT ACTION: Do not retry this failed URL. Open the next untried search result now: result-")
                    .append(resultNumber).append(". Call web_open_result result_id=\"result-")
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
     * then the tab's previous page is gone like after a successful search. If the tab did not move, the page
     * the agent had stays readable.
     */
    private void leaveProviderPage() {
        String tab = browser.currentTab();
        String shown = browser.currentUrl(tab);
        PageCapture page = pages.get(tab);
        if (page != null && page.url().equals(shown)) return;
        forget(tab);
        searchTab = tab;
        searchPage = shown;
    }

    /**
     * The address the tab shows, which web_* read even when another tool opened it (expert profile:
     * browser_navigate, clicks, tab switches). A blank tab or a non-http(s) document (blob:, srcdoc) is
     * nothing to read and is left untouched; the search provider's page after web_search is left to
     * web_open_result, also when the search found nothing.
     */
    private String requireShownPage(String tab) {
        String shown = browser.currentUrl(tab);
        if (shown.isBlank() || "about:blank".equals(shown)) {
            forget(tab);
            throw new IllegalStateException(results.isEmpty() ? NO_PAGE : AFTER_SEARCH);
        }
        if (!isWeb(shown)) {
            forget(tab);
            throw new IllegalStateException(String.format(NO_WEB_PAGE, shown));
        }
        if (!pages.containsKey(tab) && tab.equals(searchTab) && sameDocument(shown, searchPage)) {
            throw new IllegalStateException(results.isEmpty() ? NO_PAGE : AFTER_SEARCH);
        }
        return shown;
    }

    /** Same scheme, host and path: a results page that rewrites its query or fragment later is still the results page. */
    private static boolean sameDocument(String a, String b) {
        return !b.isEmpty() && a.split("[?#]", 2)[0].equalsIgnoreCase(b.split("[?#]", 2)[0]);
    }

    private void forget(String tab) {
        pages.remove(tab);
        links.clear(tab);
    }

    private static boolean isWeb(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
