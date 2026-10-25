package com.aresstack.argos.research;

import java.util.Locale;
import java.util.Set;

/**
 * Model-free default scorer: the share of distinct query terms the text contains, plus a small bonus for
 * terms in the title line, so a page about the topic outranks one that mentions it once. Relevant when
 * at least {@code minimumCoverage} of the terms occur. Good enough to keep a run on topic; a semantic
 * reranker can replace it behind {@link RelevanceScorer}.
 */
public final class LexicalRelevanceScorer implements RelevanceScorer {
    private final double minimumCoverage;

    public LexicalRelevanceScorer() {
        this(0.5);
    }

    public LexicalRelevanceScorer(double minimumCoverage) {
        this.minimumCoverage = minimumCoverage;
    }

    @Override
    public Assessment assess(String query, String document) {
        Set<String> terms = QueryTerms.of(query);
        if (terms.isEmpty()) return new Assessment(0, false);
        String text = document == null ? "" : document.toLowerCase(Locale.ROOT);
        int newline = text.indexOf('\n');
        String title = newline < 0 ? text : text.substring(0, newline);
        int present = 0;
        int inTitle = 0;
        for (String term : terms) {
            if (text.contains(term)) present++;
            if (title.contains(term)) inTitle++;
        }
        double coverage = (double) present / terms.size();
        double score = coverage + 0.25 * inTitle / terms.size();
        return new Assessment(score, present > 0 && coverage >= minimumCoverage);
    }
}
