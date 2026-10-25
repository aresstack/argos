package com.aresstack.argos.web;

import java.util.List;

/** Search hits plus short diagnostic lines (e.g. "ATTEMPT: bing.com NO_RESULTS"). */
public record SearchResults(List<SearchHit> hits, List<String> notes) {
    public SearchResults {
        hits = hits == null ? List.of() : List.copyOf(hits);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
