package com.aresstack.argos.research;

import java.util.Locale;
import java.util.Set;

/** Picks the short passage of a page that shows best why it was accepted. */
final class Passages {
    private Passages() {
    }

    /** The sentence-aligned window of at most {@code maxCharacters} with the most distinct query terms. */
    static String bestExcerpt(String text, String query, int maxCharacters) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxCharacters) return clean;
        Set<String> terms = QueryTerms.of(query);
        String[] sentences = clean.split("(?<=[.!?])\\s+");
        String best = "";
        int bestHits = -1;
        for (int start = 0; start < sentences.length; start++) {
            StringBuilder window = new StringBuilder();
            for (int end = start; end < sentences.length; end++) {
                if (window.length() > 0 && window.length() + 1 + sentences[end].length() > maxCharacters) break;
                if (window.length() > 0) window.append(' ');
                window.append(sentences[end]);
            }
            String candidate = window.length() == 0 ? sentences[start] : window.toString();
            int hits = countTerms(candidate, terms);
            if (hits > bestHits) {
                best = candidate;
                bestHits = hits;
            }
        }
        return best.length() <= maxCharacters ? best : best.substring(0, maxCharacters - 1).trim() + "…";
    }

    private static int countTerms(String text, Set<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String term : terms) {
            if (lower.contains(term)) hits++;
        }
        return hits;
    }
}
