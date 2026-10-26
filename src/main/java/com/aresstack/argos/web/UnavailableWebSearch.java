package com.aresstack.argos.web;

/** Placeholder until a search provider is configured; fails honestly instead of guessing. */
public final class UnavailableWebSearch implements WebSearchPort {
    @Override
    public SearchResults search(String query) {
        throw new IllegalStateException("web_search is not available in this Argos build yet. "
                + "Open a known site instead, e.g. web_open url=\"https://en.wikipedia.org/wiki/Special:Search?search=...\"");
    }
}
