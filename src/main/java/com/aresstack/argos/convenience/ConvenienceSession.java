package com.aresstack.argos.convenience;

import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoad;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoadOutcome;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoadRequest;
import com.aresstack.argos.search.ParallelWebSearch;
import com.aresstack.argos.web.LinkExtractor;
import com.aresstack.argos.web.PageCapture;
import com.aresstack.argos.web.SearchHit;
import com.aresstack.argos.web.UrlPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One chat's research state in the convenience profile: searches with their results, the tabs Argos opened for
 * them, and every link a page answer listed. The model only ever sees ids (s1, r-s1-3, t21, l-t21-4); the exact
 * URLs stay here and are used unchanged for navigation (no normalization: query parameters such as _sg/_tp and
 * fragments are kept). Ids are never reused: result ids carry their search, link ids their tab and keep counting
 * per tab, so an id of an earlier page, a closed tab, another chat or another browser context is rejected.
 *
 * <p>Every page answer carries title, text and links together; the answers are maps for the JSON contract.
 */
public final class ConvenienceSession {
    public static final int MAX_BATCH = 10;
    public static final int MAX_PARALLEL = 4;
    public static final int MAX_TABS = 20;
    public static final int MAX_SEARCHES = 10;
    /** Characters per web_read_tab part (as web_read). */
    public static final int PART_CHARACTERS = 12_000;
    /** All page texts of one batch answer together. */
    public static final int BATCH_TEXT_BUDGET = 40_000;
    public static final int MAX_LINKS = 150;
    /** All link entries of one batch answer together (at least 20 per page). */
    public static final int BATCH_LINK_BUDGET = 300;
    static final int FALLBACK_TEXT = 4_000;
    static final int FALLBACK_LINKS = 40;
    static final long PAGE_TIMEOUT_MILLIS = 30_000;

    private static final Pattern RESULT_ID = Pattern.compile("^r-(s\\d+)-(\\d+)$");
    private static final Pattern LINK_ID = Pattern.compile("^l-(t\\d+)-(\\d+)$");

    /** Records searches and read pages in the chat's Search History; null without one. */
    public interface History {
        void search(String query, String providers, List<SearchHit> hits);

        void page(PageCapture capture, String requestedUrl, boolean newVisit, String tool, String tab);
    }

    /** The parallel search behind web_search (tests replace it). */
    @FunctionalInterface
    public interface Search {
        List<ParallelWebSearch.EngineRun> run(String query);
    }

    record Source(String engine, int rank) {
    }

    private record Result(String id, String url, String title, String snippet, List<Source> sources) {
    }

    private record SearchRecord(String id, String query, Map<String, Result> results, List<String> engineTabs) {
    }

    private record Link(String id, String text, String url) {
    }

    private static final class TabState {
        final String id;
        final String context;
        final String origin;
        PageCapture page;
        String contentType;
        /** The links of the page the tab shows, by URL (document order) and by id. */
        final Map<String, Link> byUrl = new LinkedHashMap<>();
        final Map<String, Link> byId = new HashMap<>();
        boolean linksTruncated;
        int nextLink = 1;
        /** Pages this tab showed through the convenience tools, for web_back's domain slot. */
        final Deque<String> history = new ArrayDeque<>();

        TabState(String id, String context, String origin) {
            this.id = id;
            this.context = context;
            this.origin = origin;
        }
    }

    private final ConvenienceBrowser browser;
    private final NavigationGate gate;
    private final UrlPolicy policy;
    private final Search search;
    private final History history;
    private final LinkExtractor extractor = new LinkExtractor();
    private final Map<String, SearchRecord> searches = new LinkedHashMap<>();
    private final Map<String, TabState> tabs = new LinkedHashMap<>();
    private int nextSearch = 1;

    public ConvenienceSession(ConvenienceBrowser browser, NavigationGate gate, UrlPolicy policy, Search search,
                              History history) {
        this.browser = browser;
        this.gate = gate;
        this.policy = policy;
        this.search = search;
        this.history = history;
    }

    // ---- web_search ---------------------------------------------------------------------------------------

    public synchronized Map<String, Object> search(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search query.");
        List<String> closed = closePreviousEngineTabs();
        List<ParallelWebSearch.EngineRun> runs = search.run(query.trim());
        String searchId = "s" + nextSearch++;
        List<Result> merged = merge(searchId, runs);
        Map<String, Result> byId = new LinkedHashMap<>();
        merged.forEach(result -> byId.put(result.id(), result));
        List<String> engineTabs = new ArrayList<>();
        String context = browser.contextId();
        List<Map<String, Object>> engines = new ArrayList<>();
        boolean usable = false;
        boolean complete = true;
        for (ParallelWebSearch.EngineRun run : runs) {
            Map<String, Object> engine = new LinkedHashMap<>();
            engine.put("id", run.engine());
            engine.put("status", run.status().name());
            engine.put("tabId", run.tab());
            engine.put("resultCount", run.hits().size());
            if (!run.detail().isBlank() && run.status() != ParallelWebSearch.EngineStatus.OK) {
                engine.put("detail", run.detail());
            }
            if (!run.tab().isEmpty()) {
                engineTabs.add(run.tab());
                TabState tab = new TabState(run.tab(), context, "search " + searchId + " " + run.engine());
                tabs.put(run.tab(), tab);
                if (run.fallback() != null) {
                    adopt(tab, run.fallback(), null);
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    String text = run.fallback().text().strip();
                    fallback.put("title", run.fallback().title());
                    fallback.put("text", text.length() > FALLBACK_TEXT ? text.substring(0, FALLBACK_TEXT) : text);
                    fallback.put("truncated", text.length() > FALLBACK_TEXT);
                    fallback.put("links", links(tab, FALLBACK_LINKS));
                    fallback.put("linksTruncated", tab.byId.size() > FALLBACK_LINKS || tab.linksTruncated);
                    engine.put("fallbackPage", fallback);
                    usable = true;
                }
            }
            switch (run.status()) {
                case OK, NO_RESULTS -> usable = true;
                default -> complete = false;
            }
            engines.add(engine);
        }
        searches.put(searchId, new SearchRecord(searchId, query.trim(), byId, engineTabs));
        while (searches.size() > MAX_SEARCHES) searches.remove(searches.keySet().iterator().next());
        recordSearch(query.trim(), runs, merged);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("searchId", searchId);
        answer.put("query", query.trim());
        answer.put("status", complete ? "COMPLETE" : usable ? "PARTIAL" : "FAILED");
        answer.put("engines", engines);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Result result : merged) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resultId", result.id());
            item.put("title", result.title());
            item.put("snippet", result.snippet());
            item.put("domain", RegistrableDomain.display(result.url()));
            List<Map<String, Object>> sources = new ArrayList<>();
            result.sources().forEach(source -> sources.add(Map.of("engine", source.engine(), "rank", source.rank())));
            item.put("sources", sources);
            results.add(item);
        }
        answer.put("results", results);
        if (!closed.isEmpty()) answer.put("closedTabs", closed);
        return answer;
    }

    /**
     * One list from all engines, in a fixed order that is no ranking: rank 1 of every engine (in configuration
     * order), then rank 2, and so on. Hits with the identical URL string are one result with all their sources;
     * URLs are never normalized for this, so different query parameters stay different results.
     */
    static List<Result> merge(String searchId, List<ParallelWebSearch.EngineRun> runs) {
        Map<String, Result> byUrl = new LinkedHashMap<>();
        int deepest = runs.stream().mapToInt(run -> run.hits().size()).max().orElse(0);
        for (int position = 0; position < deepest; position++) {
            for (ParallelWebSearch.EngineRun run : runs) {
                if (position >= run.hits().size()) continue;
                ParallelWebSearch.EngineHit hit = run.hits().get(position);
                Result known = byUrl.get(hit.url());
                if (known != null) {
                    known.sources().add(new Source(run.engine(), hit.rank()));
                    continue;
                }
                List<Source> sources = new ArrayList<>();
                sources.add(new Source(run.engine(), hit.rank()));
                byUrl.put(hit.url(), new Result("r-" + searchId + "-" + (byUrl.size() + 1), hit.url(), hit.title(),
                        hit.snippet() == null ? "" : hit.snippet().strip(), sources));
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    private void recordSearch(String query, List<ParallelWebSearch.EngineRun> runs, List<Result> merged) {
        if (history == null) return;
        try {
            List<String> providers = new ArrayList<>();
            runs.forEach(run -> providers.add(run.engine() + " " + run.status()));
            List<SearchHit> hits = new ArrayList<>();
            merged.forEach(result -> hits.add(new SearchHit(result.title(), result.url(), result.snippet())));
            history.search(query, String.join(", ", providers), hits);
        } catch (RuntimeException ignored) {
            // Recording never fails the search.
        }
    }

    /** The engine tabs of earlier searches are not research paths: they close when the next search starts. */
    private List<String> closePreviousEngineTabs() {
        List<String> closed = new ArrayList<>();
        for (SearchRecord record : searches.values()) {
            for (String tab : record.engineTabs()) {
                TabState state = tabs.get(tab);
                if (state == null || !state.origin.startsWith("search ")) continue;
                tabs.remove(tab);
                try {
                    browser.closeTabQuietly(tab);
                } catch (RuntimeException ignored) {
                    // Gone already.
                }
                closed.add(tab);
            }
        }
        return closed;
    }

    // ---- opening pages by id --------------------------------------------------------------------------------

    private record Target(String ref, String refKey, String url, String origin) {
    }

    public synchronized Map<String, Object> selectResults(String searchId, List<String> resultIds) {
        if (resultIds == null || resultIds.isEmpty()) throw new IllegalArgumentException("Give at least one resultId.");
        if (resultIds.size() > MAX_BATCH) throw new IllegalArgumentException("Select at most " + MAX_BATCH + " results per call.");
        String sid = searchId == null ? "" : searchId.trim().toLowerCase(Locale.ROOT);
        SearchRecord record = searches.get(sid);
        if (record == null) {
            throw new IllegalArgumentException("Unknown or expired searchId: " + searchId + ". Use the searchId of a recent web_search.");
        }
        List<Target> targets = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String raw : resultIds) {
            String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            Matcher match = RESULT_ID.matcher(id);
            Result result = record.results().get(id);
            if (result == null) {
                errors.add(error("resultId", raw, match.matches() && !match.group(1).equals(sid)
                        ? "belongs to another search than " + sid : "unknown result id in " + sid));
                continue;
            }
            targets.add(new Target(id, "resultId", result.url(), id));
        }
        return openInNewTabs(targets, errors, "web_select_results");
    }

    public record LinkChoice(String tabId, String linkId) {
    }

    public synchronized Map<String, Object> selectLinks(List<LinkChoice> choices) {
        if (choices == null || choices.isEmpty()) throw new IllegalArgumentException("Give at least one {tabId, linkId}.");
        if (choices.size() > MAX_BATCH) throw new IllegalArgumentException("Select at most " + MAX_BATCH + " links per call.");
        List<Target> targets = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (LinkChoice choice : choices) {
            try {
                Link link = link(choice.tabId(), choice.linkId());
                targets.add(new Target(link.id(), "linkId", link.url(), link.id()));
            } catch (IllegalArgumentException invalid) {
                errors.add(error("linkId", choice.linkId(), invalid.getMessage()));
            }
        }
        return openInNewTabs(targets, errors, "web_select_links");
    }

    public synchronized Map<String, Object> openUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Give the URL the user provided.");
        policy.check(url.trim());
        Map<String, Object> answer = openInNewTabs(List.of(new Target(url.trim(), "requested", url.trim(), "web_open_url")),
                new ArrayList<>(), "web_open_url");
        return answer;
    }

    /**
     * Opens each target in a new tab: duplicates (same id, same exact URL, already open) are skipped, the rest load
     * in parallel under the domain gate; the answer keeps the order of the selection.
     */
    private Map<String, Object> openInNewTabs(List<Target> targets, List<Map<String, Object>> errors, String tool) {
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Target> pending = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        Map<String, String> urls = new HashMap<>();
        for (Target target : targets) {
            if (!refs.add(target.ref())) {
                skipped.add(skip(target, "listed twice"));
                continue;
            }
            String same = urls.get(target.url());
            if (same != null) {
                skipped.add(skip(target, "same target as " + same));
                continue;
            }
            String open = tabShowing(target.url());
            if (open != null) {
                Map<String, Object> skip = skip(target, "already open");
                skip.put("tabId", open);
                skipped.add(skip);
                continue;
            }
            try {
                policy.check(target.url());
            } catch (IllegalArgumentException blocked) {
                errors.add(error(target.refKey(), target.ref(), "blocked: " + blocked.getMessage()));
                continue;
            }
            urls.put(target.url(), target.ref());
            pending.add(target);
        }
        int room = MAX_TABS - openTabCount();
        while (pending.size() > Math.max(0, room)) {
            Target over = pending.remove(pending.size() - 1);
            skipped.add(skip(over, "tab limit of " + MAX_TABS + " reached; close tabs with web_close_tabs"));
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        List<Map<String, Object>> opened = new ArrayList<>();
        if (!pending.isEmpty()) {
            List<TabLoadRequest> requests = new ArrayList<>();
            pending.forEach(target -> requests.add(new TabLoadRequest(null, target.url(), RegistrableDomain.of(target.url()))));
            List<TabLoad> loads = browser.loadTabs(requests, gate, MAX_PARALLEL, PAGE_TIMEOUT_MILLIS);
            String context = browser.contextId();
            List<TabState> states = new ArrayList<>();
            List<Target> openedTargets = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                Target target = pending.get(i);
                TabLoad load = loads.get(i);
                Map<String, Object> failure = failure(target, load);
                if (failure != null) {
                    errors.add(failure);
                    if (!load.tab().isEmpty()) closeQuietly(load.tab());
                    continue;
                }
                TabState tab = new TabState(load.tab(), context, target.origin());
                tabs.put(load.tab(), tab);
                adopt(tab, load.capture(), load.contentType());
                recordPage(load.capture(), target.url(), tool, load.tab());
                states.add(tab);
                openedTargets.add(target);
            }
            int textShare = Math.max(2_000, Math.min(PART_CHARACTERS, BATCH_TEXT_BUDGET / Math.max(1, states.size())));
            int linkShare = Math.max(20, Math.min(MAX_LINKS, BATCH_LINK_BUDGET / Math.max(1, states.size())));
            for (int i = 0; i < states.size(); i++) {
                Map<String, Object> page = new LinkedHashMap<>();
                Target target = openedTargets.get(i);
                if (!target.refKey().equals("requested")) page.put(target.refKey(), target.ref());
                page.putAll(pageAnswer(states.get(i), 1, textShare, linkShare));
                opened.add(page);
            }
        }
        answer.put("opened", opened);
        answer.put("skipped", skipped);
        answer.put("errors", errors);
        return answer;
    }

    /** null when the load delivered a readable page; else the error entry (HTTP errors, challenges, timeouts). */
    private Map<String, Object> failure(Target target, TabLoad load) {
        if (load.outcome() != TabLoadOutcome.OK) {
            Map<String, Object> error = error(target.refKey(), target.ref(), switch (load.outcome()) {
                case COOLING_DOWN -> "the site refused recent requests; not opened";
                case TIMEOUT -> "timeout: " + load.error();
                case DOWNLOAD -> "a download, not a page; use the document tools";
                default -> "navigation failed: " + firstLine(load.error());
            });
            error.put("domain", RegistrableDomain.display(target.url()));
            if (load.retryAfterSeconds() > 0) error.put("retryAfterSeconds", load.retryAfterSeconds());
            return error;
        }
        PageCapture capture = load.capture();
        try {
            policy.check(capture.url());
        } catch (IllegalArgumentException blocked) {
            return error(target.refKey(), target.ref(), "blocked after redirect: " + blocked.getMessage());
        }
        int status = capture.status();
        if (load.challenge() || status == 429 || status == 403) {
            Map<String, Object> error = error(target.refKey(), target.ref(), load.challenge()
                    ? "access challenge (not bypassed)" : "HTTP " + status + " (access limited)");
            error.put("domain", RegistrableDomain.display(capture.url()));
            if (load.retryAfterSeconds() > 0) error.put("retryAfterSeconds", load.retryAfterSeconds());
            return error;
        }
        if (capture.isError()) {
            Map<String, Object> error = error(target.refKey(), target.ref(), "HTTP " + status);
            error.put("domain", RegistrableDomain.display(capture.url()));
            return error;
        }
        return null;
    }

    // ---- one tab ----------------------------------------------------------------------------------------------

    public synchronized Map<String, Object> followLink(String tabId, String linkId) {
        TabState tab = tab(tabId);
        Link link = link(tab.id, linkId);
        policy.check(link.url());
        TabLoad load = browser.loadTabs(List.of(new TabLoadRequest(tab.id, link.url(), RegistrableDomain.of(link.url()))),
                gate, 1, PAGE_TIMEOUT_MILLIS).get(0);
        Map<String, Object> failure = failure(new Target(link.id(), "linkId", link.url(), link.id()), load);
        if (failure != null) {
            throw new IllegalStateException(String.valueOf(failure.get("error"))
                    + (failure.containsKey("retryAfterSeconds") ? " (retry after " + failure.get("retryAfterSeconds") + " s)" : "")
                    + ". The tab keeps its previous page.");
        }
        adopt(tab, load.capture(), load.contentType());
        recordPage(load.capture(), link.url(), "web_follow_link", tab.id);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("fromLinkId", link.id());
        answer.putAll(pageAnswer(tab, 1, PART_CHARACTERS, MAX_LINKS));
        return answer;
    }

    public synchronized Map<String, Object> readTab(String tabId, int part) {
        TabState tab = tab(tabId);
        TabLoad load = browser.readTab(tab.id);
        policy.check(load.capture().url());
        boolean moved = tab.page == null || !tab.page.url().equals(load.capture().url());
        adopt(tab, load.capture(), load.contentType() == null ? tab.contentType : load.contentType());
        if (history != null) {
            try {
                history.page(load.capture(), null, moved, "web_read_tab", tab.id);
            } catch (RuntimeException ignored) {
                // Recording never fails the tool.
            }
        }
        return pageAnswer(tab, part, PART_CHARACTERS, MAX_LINKS);
    }

    public synchronized Map<String, Object> back(String tabId) {
        TabState tab = tab(tabId);
        if (tab.history.size() < 2) throw new IllegalStateException("No previous page in tab " + tab.id + ".");
        String current = tab.history.pop();
        String previous = tab.history.peek();
        String domain = RegistrableDomain.of(previous);
        NavigationGate.Lease lease = awaitSlot(domain);
        TabLoad load;
        try {
            load = browser.backTab(tab.id);
        } catch (RuntimeException failed) {
            lease.release(0, 0, false);
            tab.history.push(current);
            throw failed;
        }
        if (load == null) {
            lease.release(0, 0, false);
            tab.history.push(current);
            throw new IllegalStateException("No previous page in tab " + tab.id + ".");
        }
        lease.release(load.capture().status(), load.retryAfterSeconds(), load.challenge());
        tab.history.pop();               // adopt pushes the page again
        try {
            policy.check(load.capture().url());
        } catch (IllegalArgumentException blocked) {
            closeQuietly(tab.id);
            throw new IllegalArgumentException("Blocked after going back: " + blocked.getMessage());
        }
        adopt(tab, load.capture(), load.contentType());
        recordPage(load.capture(), null, "web_back", tab.id);
        return pageAnswer(tab, 1, PART_CHARACTERS, MAX_LINKS);
    }

    public synchronized String url(String referenceId) {
        String id = referenceId == null ? "" : referenceId.trim().toLowerCase(Locale.ROOT);
        Matcher result = RESULT_ID.matcher(id);
        if (result.matches()) {
            SearchRecord record = searches.get(result.group(1));
            Result found = record == null ? null : record.results().get(id);
            if (found == null) throw new IllegalArgumentException("Unknown or expired result id: " + referenceId + ".");
            return found.url();
        }
        Matcher link = LINK_ID.matcher(id);
        if (link.matches()) return link(link.group(1), id).url();
        throw new IllegalArgumentException("Unknown reference: " + referenceId + ". Use a resultId (r-s1-2) or a linkId (l-t21-4).");
    }

    public synchronized Map<String, Object> closeTabs(List<String> tabIds) {
        if (tabIds == null || tabIds.isEmpty()) throw new IllegalArgumentException("Give at least one tabId.");
        List<String> closed = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String raw : tabIds) {
            String id = normalizeTab(raw);
            TabState state = tabs.get(id);
            if (state == null) {
                errors.add(error("tabId", raw, "unknown tab (not opened by these tools in this chat)"));
                continue;
            }
            tabs.remove(id);
            closeQuietly(id);
            closed.add(id);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("closed", closed);
        answer.put("errors", errors);
        answer.put("openTabs", new ArrayList<>(tabs.keySet()));
        return answer;
    }

    /**
     * The browser tab id behind a tab of these tools (open, in the current context), for the screenshot and
     * developer tools; unknown, closed or foreign tabs are rejected like in every other tab tool.
     */
    public synchronized String requireTab(String tabId) {
        return tab(tabId).id;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private TabState tab(String tabId) {
        String id = normalizeTab(tabId);
        TabState tab = tabs.get(id);
        if (tab == null) throw new IllegalArgumentException("Unknown tab " + tabId + ": not opened by these tools in this chat, or closed.");
        if (!tab.context.equals(browser.contextId())) {
            throw new IllegalArgumentException("Tab " + id + " belongs to another browser context; switch back to use it.");
        }
        if (!browser.hasOpenTab(id)) {
            tabs.remove(id);
            throw new IllegalArgumentException("Tab " + id + " was closed.");
        }
        return tab;
    }

    /** A link of the page the tab shows now; ids of earlier pages and closed tabs are rejected. */
    private Link link(String tabId, String linkId) {
        String id = linkId == null ? "" : linkId.trim().toLowerCase(Locale.ROOT);
        Matcher match = LINK_ID.matcher(id);
        String tabOfId = match.matches() ? match.group(1) : "";
        String tab = normalizeTab(tabId);
        if (!match.matches()) throw new IllegalArgumentException("Unknown link id: " + linkId + ". Use a linkId such as l-t21-4.");
        if (!tabOfId.equals(tab)) throw new IllegalArgumentException(id + " belongs to tab " + tabOfId + ", not " + tab + ".");
        TabState state = tabs.get(tab);
        if (state == null) throw new IllegalArgumentException("Stale link id " + id + ": tab " + tab + " is closed or unknown in this chat.");
        if (!state.context.equals(browser.contextId())) {
            throw new IllegalArgumentException("Tab " + tab + " belongs to another browser context.");
        }
        Link link = state.byId.get(id);
        if (link == null) {
            int number = Integer.parseInt(match.group(2));
            throw new IllegalArgumentException(number < state.nextLink
                    ? "Stale link id " + id + ": it belongs to an earlier page of " + tab + "."
                    : "Unknown link id " + id + " in " + tab + ".");
        }
        return link;
    }

    /** Takes the page a tab now shows; a new page gets new link ids (the counter of the tab never goes back). */
    private void adopt(TabState tab, PageCapture capture, String contentType) {
        boolean samePage = tab.page != null && tab.page.url().equals(capture.url());
        if (!samePage) {
            tab.byUrl.clear();
            tab.byId.clear();
            tab.history.push(capture.url());
        }
        tab.page = capture;
        tab.contentType = contentType;
        tab.linksTruncated = false;
        for (LinkExtractor.Anchor anchor : extractor.extract(capture.html(), capture.url())) {
            Link known = tab.byUrl.get(anchor.url());
            if (known != null) continue;
            if (tab.byUrl.size() >= MAX_LINKS) {
                tab.linksTruncated = true;
                continue;
            }
            Link link = new Link("l-" + tab.id + "-" + tab.nextLink++, anchor.text(), anchor.url());
            tab.byUrl.put(anchor.url(), link);
            tab.byId.put(link.id(), link);
        }
    }

    private Map<String, Object> pageAnswer(TabState tab, int part, int maxText, int maxLinks) {
        PageCapture page = tab.page;
        String text = page.text().strip();
        int parts = Math.max(1, (text.length() + PART_CHARACTERS - 1) / PART_CHARACTERS);
        if (part < 1 || part > parts) throw new IllegalArgumentException("This page has " + parts + " part(s); use part 1 to " + parts + ".");
        int start = (part - 1) * PART_CHARACTERS;
        String slice = text.substring(start, Math.min(text.length(), start + PART_CHARACTERS));
        boolean cut = slice.length() > maxText;
        if (cut) slice = slice.substring(0, maxText);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("tabId", tab.id);
        answer.put("title", page.title());
        answer.put("domain", RegistrableDomain.display(page.url()));
        answer.put("status", "OK");
        if (page.status() > 0) answer.put("httpStatus", page.status());
        answer.put("contentType", tab.contentType == null ? "text/html" : tab.contentType.split(";")[0].trim());
        answer.put("text", slice);
        answer.put("part", part);
        answer.put("parts", parts);
        answer.put("truncated", cut || parts > part);
        answer.put("links", links(tab, maxLinks));
        answer.put("linksTruncated", tab.linksTruncated || tab.byId.size() > maxLinks);
        return answer;
    }

    private static List<Map<String, Object>> links(TabState tab, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Link link : tab.byUrl.values()) {
            if (out.size() >= max) break;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("linkId", link.id());
            item.put("text", link.text().isEmpty() ? "(no text)" : link.text());
            item.put("domain", RegistrableDomain.display(link.url()));
            out.add(item);
        }
        return out;
    }

    /** The open tab of this chat that shows exactly {@code url}, or null. */
    private String tabShowing(String url) {
        for (TabState tab : tabs.values()) {
            if (tab.page != null && tab.page.url().equals(url) && !tab.origin.startsWith("search ")) return tab.id;
        }
        return null;
    }

    private int openTabCount() {
        return tabs.size();
    }

    private void closeQuietly(String tab) {
        try {
            browser.closeTabQuietly(tab);
        } catch (RuntimeException ignored) {
            // Gone already.
        }
    }

    private void recordPage(PageCapture capture, String requestedUrl, String tool, String tab) {
        if (history == null) return;
        try {
            history.page(capture, requestedUrl, true, tool, tab);
        } catch (RuntimeException ignored) {
            // Recording never fails the tool.
        }
    }

    /** Waits off the browser thread for the domain's slot (web_back); fails after the page timeout. */
    private NavigationGate.Lease awaitSlot(String domain) {
        long cooldown = gate.cooldownMillis(domain);
        if (cooldown > 0) {
            throw new IllegalStateException(domain + " refused recent requests; try again in about "
                    + (cooldown + 999) / 1000 + " s.");
        }
        long deadline = System.currentTimeMillis() + PAGE_TIMEOUT_MILLIS;
        while (true) {
            NavigationGate.Lease lease = gate.tryAcquire(domain);
            if (lease != null) return lease;
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Another navigation of " + domain + " is still running; try again.");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + domain + ".");
            }
        }
    }

    private static Map<String, Object> skip(Target target, String reason) {
        Map<String, Object> skip = new LinkedHashMap<>();
        if (!target.refKey().equals("requested")) skip.put(target.refKey(), target.ref());
        skip.put("reason", reason);
        return skip;
    }

    private static Map<String, Object> error(String key, String ref, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        if (!key.equals("requested")) error.put(key, ref);
        error.put("error", message);
        return error;
    }

    static String normalizeTab(String tabId) {
        String tab = tabId == null ? "" : tabId.trim().toLowerCase(Locale.ROOT);
        return tab.matches("\\d+") ? "t" + tab : tab;
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 160 ? line.substring(0, 160) : line;
    }
}
