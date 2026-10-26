package com.aresstack.argos.research;

/**
 * An accepted source.
 *
 * @param foundVia the search query or the URL of the page that linked to it
 */
public record ResearchSource(String url, String host, String title, double score, String excerpt,
                             String foundVia) {
}
