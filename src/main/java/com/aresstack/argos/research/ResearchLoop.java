package com.aresstack.argos.research;

import com.aresstack.argos.web.SearchHit;
import com.aresstack.argos.web.SearchResults;
import com.aresstack.argos.web.WebSearchPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * One deterministic research run, ported from AskAI's {@code ResearchLoop} and the acquisition engine
 * {@code WebSearchApplicationService}: search → ranked seed frontier → read page → relevance gate →
 * accept source → follow only the page's relevant links → repeat, with one central budget gate before
 * every search and every page read. Single use; not thread-safe.
 *
 * <p>Kept from AskAI: frontier with visited/redirect identity, search-provider pages as transit, a blocked
 * site family is never re-opened, irrelevant pages are not link-expanded, links are judged on their anchor
 * text against the bar set by the selected search hits, explicit stop reasons, sufficiency = sources on
 * distinct hosts. Left out: the host state machine, HUD, user challenge waiting, scope control and the
 * mandatory cross-encoder (a {@link RelevanceScorer} may be one).</p>
 */
final class ResearchLoop {
    private static final int MAX_ASSESSED_LINKS_PER_PAGE = 60;
    private static final int MAX_LISTED_DOCUMENTS = 8;
    private static final int MAX_SCORED_CHARACTERS = 20_000;
    private static final int EXCERPT_CHARACTERS = 320;

    private record Candidate(String url, int depth, String foundVia) {
    }

    private final String question;
    private final WebSearchPort search;
    private final ResearchPageReader reader;
    private final RelevanceScorer scorer;
    private final QueryPlanner planner;
    private final ResearchBudget budget;
    private final LongSupplier clock;
    private final BooleanSupplier cancelled;
    private final ResearchListener listener;
    private final long startedAt;

    private final Deque<Candidate> frontier = new ArrayDeque<>();
    /** Every address the run knows (queued or read), so nothing is queued twice. */
    private final Set<String> seen = new HashSet<>();
    /** Addresses actually navigated, requested and final; a page is never read twice. */
    private final Set<String> visited = new HashSet<>();
    private final Set<String> sourceFingerprints = new HashSet<>();
    private final Set<String> providerFamilies = new HashSet<>();
    private final Set<String> blockedFamilies = new HashSet<>();
    private final Set<String> hosts = new LinkedHashSet<>();
    private final Set<String> documents = new LinkedHashSet<>();
    private final List<ResearchSource> sources = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int searches;
    private int pagesRead;
    private int consecutiveErrors;
    private int errors;
    private int rejectedPages;
    private int blockedPages;
    private int duplicates;
    /** Lowest score among the search hits judged relevant: the bar a link anchor has to clear. */
    private Double searchHitFloor;

    ResearchLoop(String question, WebSearchPort search, ResearchPageReader reader, RelevanceScorer scorer,
                 QueryPlanner planner, ResearchBudget budget, LongSupplier clock, BooleanSupplier cancelled,
                 ResearchListener listener) {
        this.question = question.trim();
        this.search = search;
        this.reader = reader;
        this.scorer = scorer;
        this.planner = planner;
        this.budget = budget;
        this.clock = clock;
        this.cancelled = cancelled;
        this.listener = listener;
        this.startedAt = clock.getAsLong();
    }

    ResearchReport run() {
        ResearchStopReason seeded = seedFromSearch();
        return finish(seeded != null ? seeded : explore());
    }

    private ResearchStopReason seedFromSearch() {
        boolean answered = false;
        boolean challenged = false;
        for (String query : plannedQueries()) {
            ResearchStopReason stop = stopReasonNow();
            if (stop != null) return stop;
            report("searching: " + query);
            SearchResults results;
            try {
                searches++;
                results = search.search(query);
            } catch (Exception failure) {
                notes.add("SEARCH: \"" + query + "\" FAILED " + describe(failure));
                continue;
            }
            answered = true;
            for (String note : results.notes()) {
                if (note.startsWith("PROVIDER: ")) {
                    providerFamilies.add(ResearchUrls.family(note.substring("PROVIDER: ".length()).trim()));
                } else if (note.startsWith("CHALLENGE: ")) {
                    challenged |= results.hits().isEmpty();
                    notes.add(note);
                }
            }
            notes.add("SEARCH: \"" + query + "\" " + results.hits().size() + " hits");
            enqueueSearchHits(query, results.hits());
        }
        if (!frontier.isEmpty()) return null;
        if (challenged) return ResearchStopReason.CHALLENGE_REQUIRED;
        return answered ? ResearchStopReason.NO_SEARCH_RESULTS : ResearchStopReason.SEARCH_FAILED;
    }

    private List<String> plannedQueries() {
        List<String> planned;
        try {
            planned = planner.plan(question);
        } catch (Exception failure) {
            notes.add("PLAN: query planning failed " + describe(failure) + ", searching the question itself");
            planned = List.of(question);
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String query : planned) {
            if (query != null && !query.isBlank()) distinct.add(query.trim());
        }
        if (distinct.isEmpty()) distinct.add(question);
        return distinct.stream().limit(budget.maxSearches()).toList();
    }

    /** Relevant hits first, in their scorer order (stable: engine rank breaks ties), the rest after them. */
    private void enqueueSearchHits(String query, List<SearchHit> hits) {
        record Scored(SearchHit hit, RelevanceScorer.Assessment assessment) {
        }
        List<Scored> scored = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (!ResearchUrls.isHttp(hit.url())) continue;
            scored.add(new Scored(hit, scorer.assess(question, hit.title() + "\n" + hit.snippet())));
        }
        scored.sort(Comparator.comparing((Scored s) -> !s.assessment().relevant())
                .thenComparing(s -> -s.assessment().score()));
        for (Scored entry : scored) {
            if (entry.assessment().relevant()) {
                double score = entry.assessment().score();
                searchHitFloor = searchHitFloor == null ? score : Math.min(searchHitFloor, score);
            }
            String url = entry.hit().url();
            if (ResearchUrls.isDocument(url)) {
                listDocument(url);
            } else if (seen.add(ResearchUrls.canonical(url))) {
                frontier.addLast(new Candidate(url, 0, "search \"" + query + "\""));
            }
        }
    }

    private ResearchStopReason explore() {
        while (true) {
            ResearchStopReason stop = stopReasonNow();
            if (stop != null) return stop;
            Candidate candidate = frontier.pollFirst();
            if (candidate == null) return ResearchStopReason.NO_RELEVANT_PATHS;
            String family = ResearchUrls.family(candidate.url());
            if (blockedFamilies.contains(family) || providerFamilies.contains(family)
                    || !visited.add(ResearchUrls.canonical(candidate.url()))) {
                continue;
            }
            report("reading: " + candidate.url());
            ResearchPage page;
            try {
                pagesRead++;
                page = reader.read(candidate.url());
                consecutiveErrors = 0;
            } catch (Exception failure) {
                errors++;
                consecutiveErrors++;
                notes.add("ERROR: " + ResearchUrls.host(candidate.url()) + " " + describe(failure));
                continue;
            }
            visit(candidate, page);
        }
    }

    private void visit(Candidate candidate, ResearchPage page) {
        String finalUrl = page.url() == null || page.url().isBlank() ? candidate.url() : page.url();
        String finalFamily = ResearchUrls.family(finalUrl);
        String finalCanonical = ResearchUrls.canonical(finalUrl);
        seen.add(finalCanonical);
        if (!finalCanonical.equals(ResearchUrls.canonical(candidate.url())) && !visited.add(finalCanonical)) {
            duplicates++; // a redirect onto a page this run already read
            return;
        }
        if (page.access() != ResearchPage.Access.READABLE) {
            blockedPages++;
            if (blockedFamilies.add(finalFamily)) {
                notes.add("BLOCKED: " + ResearchUrls.host(finalUrl) + " " + page.access()
                        + (page.status() > 0 ? " (HTTP " + page.status() + ")" : "") + ", site skipped");
            }
            return;
        }
        if (providerFamilies.contains(finalFamily)) return; // search engine transit, never a source
        String text = page.text().length() > MAX_SCORED_CHARACTERS
                ? page.text().substring(0, MAX_SCORED_CHARACTERS) : page.text();
        RelevanceScorer.Assessment assessment = scorer.assess(question, page.title() + "\n" + text);
        if (!assessment.relevant()) {
            rejectedPages++; // and not link-expanded: an off-topic page is no bridge to on-topic ones
            report("not relevant: " + finalUrl);
            return;
        }
        if (!sourceFingerprints.add(fingerprint(text))) {
            duplicates++;
            return;
        }
        String host = ResearchUrls.host(finalUrl);
        hosts.add(host.startsWith("www.") ? host.substring(4) : host);
        sources.add(new ResearchSource(finalUrl, host, page.title(), assessment.score(),
                Passages.bestExcerpt(text, question, EXCERPT_CHARACTERS), candidate.foundVia()));
        report("accepted: " + finalUrl);
        if (candidate.depth() < budget.maxLinkDepth()) followRelevantLinks(page, finalUrl, candidate.depth());
    }

    /** AskAI {@code selectLinksToFollow}: judged on anchor text only, bounded, best first, above the bar. */
    private void followRelevantLinks(ResearchPage page, String pageUrl, int depth) {
        record ScoredLink(String url, double score) {
        }
        List<ScoredLink> relevant = new ArrayList<>();
        Set<String> assessed = new HashSet<>();
        for (ResearchPage.PageLink link : page.links()) {
            if (assessed.size() >= MAX_ASSESSED_LINKS_PER_PAGE) break;
            String url = link.url();
            if (!ResearchUrls.isHttp(url)) continue;
            String canonical = ResearchUrls.canonical(url);
            String family = ResearchUrls.family(url);
            if (seen.contains(canonical) || providerFamilies.contains(family) || blockedFamilies.contains(family)
                    || link.text().isBlank() || !assessed.add(canonical)) {
                continue;
            }
            RelevanceScorer.Assessment assessment = scorer.assess(question, link.text() + "\n");
            if (!assessment.relevant() || (searchHitFloor != null && assessment.score() < searchHitFloor)) continue;
            if (ResearchUrls.isDocument(url)) {
                listDocument(url);
            } else {
                relevant.add(new ScoredLink(url, assessment.score()));
            }
        }
        relevant.sort(Comparator.comparingDouble(ScoredLink::score).reversed());
        for (ScoredLink link : relevant.subList(0, Math.min(budget.maxLinksPerPage(), relevant.size()))) {
            seen.add(ResearchUrls.canonical(link.url()));
            frontier.addLast(new Candidate(link.url(), depth + 1, pageUrl));
        }
    }

    private void listDocument(String url) {
        if (documents.size() < MAX_LISTED_DOCUMENTS) documents.add(url);
    }

    /** The one central gate (AskAI {@code stopReasonNow}): the normal end is checked before any limit. */
    private ResearchStopReason stopReasonNow() {
        if (cancelled.getAsBoolean()) return ResearchStopReason.CANCELLED;
        if (sources.size() >= budget.minimumSources() && hosts.size() >= budget.minimumDistinctHosts()) {
            return ResearchStopReason.SUFFICIENT_EVIDENCE;
        }
        if (sources.size() >= budget.maxSources()) return ResearchStopReason.SOURCE_BUDGET_EXHAUSTED;
        if (pagesRead >= budget.maxPagesRead()) return ResearchStopReason.PAGE_BUDGET_EXHAUSTED;
        if (consecutiveErrors >= budget.maxConsecutiveErrors()) return ResearchStopReason.ERROR_BUDGET_EXHAUSTED;
        if (elapsed() >= budget.maxDuration().toMillis()) return ResearchStopReason.TIME_BUDGET_EXHAUSTED;
        return null;
    }

    private ResearchReport finish(ResearchStopReason reason) {
        if (rejectedPages + blockedPages + duplicates > 0) {
            notes.add("SKIPPED: " + rejectedPages + " not relevant, " + blockedPages + " blocked, "
                    + duplicates + " duplicates");
        }
        report("stopped: " + reason);
        return new ResearchReport(question, reason, sources, List.copyOf(documents), progress(), notes);
    }

    private void report(String activity) {
        try {
            listener.onProgress(progress(), activity);
        } catch (RuntimeException ignored) {
            // Progress is a courtesy; it never ends a run.
        }
    }

    private ResearchProgress progress() {
        return new ResearchProgress(searches, pagesRead, sources.size(), hosts.size(), consecutiveErrors, errors,
                rejectedPages, blockedPages, duplicates, elapsed());
    }

    private long elapsed() {
        return clock.getAsLong() - startedAt;
    }

    /** Mirrors of one page under other addresses carry the same text. */
    private static String fingerprint(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 2_000 ? normalized.substring(0, 2_000) : normalized;
    }

    /**
     * One short line: query strings are dropped (they may carry tokens) and the text is cut, so that a
     * stack of wrapped causes never reaches the model.
     */
    private static String describe(Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        message = message.replaceAll("(https?://[^\\s?#\"']+)[?#][^\\s\"']*", "$1?…")
                .replaceAll("\\s+", " ").trim();
        return message.length() > 160 ? message.substring(0, 159) + "…" : message;
    }
}
