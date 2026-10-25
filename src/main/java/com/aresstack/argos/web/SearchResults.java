package com.aresstack.argos.web;

import java.util.List;

/** Hits in rank order plus short diagnostic lines (e.g. "ATTEMPT: www.bing.com NO_ORGANIC_RESULTS"). */
public record SearchResults(List<SearchHit> hits, List<String> notes) {
    public SearchResults {
        hits = List.copyOf(hits);
        notes = List.copyOf(notes);
    }
}
