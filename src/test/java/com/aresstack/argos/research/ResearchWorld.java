package com.aresstack.argos.research;

import com.aresstack.argos.web.SearchHit;
import com.aresstack.argos.web.SearchResults;
import com.aresstack.argos.web.WebSearchPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** In-memory web for research tests: scripted search results, pages by URL, a manual clock. */
final class ResearchWorld implements WebSearchPort, ResearchPageReader {
    final Map<String, SearchResults> searches = new HashMap<>();
    final Map<String, ResearchPage> pages = new HashMap<>();
    final Map<String, Exception> failures = new HashMap<>();
    final List<String> queried = new ArrayList<>();
    final List<String> read = new ArrayList<>();
    long now = 1_000;
    long millisPerRead = 1_000;
    Consumer<String> onRead = url -> { };

    ResearchWorld hits(String query, SearchHit... hits) {
        searches.put(query, new SearchResults(List.of(hits), List.of("PROVIDER: www.bing.com")));
        return this;
    }

    ResearchWorld page(String url, String title, String text, ResearchPage.PageLink... links) {
        pages.put(url, new ResearchPage(url, title, 200, text, List.of(links), ResearchPage.Access.READABLE));
        return this;
    }

    static SearchHit hit(String title, String url, String snippet) {
        return new SearchHit(title, url, snippet);
    }

    static ResearchPage.PageLink link(String text, String url) {
        return new ResearchPage.PageLink(text, url);
    }

    ResearchService service(ResearchBudget budget) {
        return new ResearchService(this, this, new LexicalRelevanceScorer(), QueryPlanner.VERBATIM, budget,
                () -> now);
    }

    @Override
    public SearchResults search(String query) throws Exception {
        queried.add(query);
        if (failures.containsKey("search:" + query)) throw failures.get("search:" + query);
        return searches.getOrDefault(query, new SearchResults(List.of(), List.of()));
    }

    @Override
    public ResearchPage read(String url) throws Exception {
        read.add(url);
        now += millisPerRead;
        onRead.accept(url);
        if (failures.containsKey(url)) throw failures.get(url);
        ResearchPage page = pages.get(url);
        if (page == null) throw new IllegalStateException("Camoufox operation failed: NS_ERROR_UNKNOWN_HOST");
        return page;
    }
}
