package com.aresstack.argos.search;

import com.aresstack.argos.CamoufoxBrowser;
import com.aresstack.argos.web.SearchHit;
import com.aresstack.argos.web.SearchResults;
import com.aresstack.argos.web.WebSearchPort;

import java.util.ArrayList;
import java.util.List;

/** {@link WebSearchPort} backed by Argos' Camoufox session and the ported AskAI search analysis. */
public final class BrowserWebSearch implements WebSearchPort {
    private final WebSearchService service;

    public BrowserWebSearch(CamoufoxBrowser browser) {
        this(new WebSearchService(new CamoufoxSearchPageBrowser(browser), SearchSettings.fromEnvironment()));
    }

    public BrowserWebSearch(WebSearchService service) {
        this.service = service;
    }

    @Override
    public SearchResults search(String query) throws Exception {
        WebSearchService.Report report = service.search(query);
        if (report.outcome() == SearchOutcome.EXTRACTION_FAILED) {
            // Nothing readable came back: an error for the model, never a silent "No results."
            throw new SearchFailedException("web_search failed: no search engine returned a readable result page.\n"
                    + String.join("\n", notesOf(report)));
        }
        List<SearchHit> hits = report.hits().stream()
                .map(hit -> new SearchHit(hit.title(), hit.url(), hit.snippet())).toList();
        return new SearchResults(hits, notesOf(report));
    }

    /** The search reached engines but could not read any result page (layout, challenge, consent wall). */
    public static final class SearchFailedException extends Exception {
        public SearchFailedException(String message) {
            super(message);
        }
    }

    /** PROVIDER / CHALLENGE / ATTEMPT lines in AskAI's format: diagnostics, never candidates. */
    static List<String> notesOf(WebSearchService.Report report) {
        List<String> notes = new ArrayList<>();
        for (String host : report.providerHosts()) notes.add("PROVIDER: " + host);
        for (WebSearchService.ChallengeNotice challenge : report.challenges()) {
            notes.add("CHALLENGE: " + challenge.family() + " " + challenge.url()
                    + " (solve it in the Argos browser window, then search again)");
        }
        for (WebSearchService.Attempt attempt : report.attempts()) {
            notes.add("ATTEMPT: " + attempt.host() + " " + attempt.outcome());
        }
        return notes;
    }
}
