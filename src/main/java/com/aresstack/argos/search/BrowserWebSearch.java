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
        List<SearchHit> hits = report.hits().stream()
                .map(hit -> new SearchHit(hit.title(), hit.url(), hit.snippet())).toList();
        return new SearchResults(hits, notesOf(report));
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

    /** Compact numbered text for small models: "1: Title — url", indented snippet, then the notes. */
    public static String format(SearchResults results) {
        StringBuilder text = new StringBuilder();
        int number = 0;
        for (SearchHit hit : results.hits()) {
            text.append(++number).append(": ").append(hit.title()).append(" — ").append(hit.url()).append('\n');
            if (!hit.snippet().isBlank()) text.append("   ").append(hit.snippet()).append('\n');
        }
        if (results.hits().isEmpty()) text.append("No results.\n");
        for (String note : results.notes()) text.append(note).append('\n');
        return text.toString().trim();
    }
}
