package com.aresstack.argos.research;

/**
 * Port: how relevant a piece of text is to the research query. One scorer answers every relevance
 * question of a run (search hits, pages, link anchors), so its scores are comparable within the run.
 * The default is {@link LexicalRelevanceScorer}; a model reranker (AskAI's cross-encoder) fits behind
 * the same port. Scores only need to be comparable within one run, never across scorers.
 */
public interface RelevanceScorer {
    Assessment assess(String query, String document);

    /** @param relevant the scorer's own verdict; no caller applies a global threshold to {@code score} */
    record Assessment(double score, boolean relevant) {
    }
}
