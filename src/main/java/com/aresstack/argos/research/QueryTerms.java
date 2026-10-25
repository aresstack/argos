package com.aresstack.argos.research;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The words relevance is matched on (AskAI {@code WebAcquisitionText.queryTerms}): Unicode letters and
 * digits, at least three characters, so "hühner" stays one term. Common German and English function
 * words are dropped; they would make every page look relevant. The search engine never sees these terms.
 */
final class QueryTerms {
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "what", "which", "who", "how", "are", "was", "were", "does", "from",
            "about", "that", "this", "into", "than", "der", "die", "das", "und", "oder", "mit", "für", "von",
            "den", "dem", "des", "ein", "eine", "einer", "eines", "ist", "sind", "wie", "welche",
            "welcher", "wer", "auf", "bei", "aus", "nach", "über", "zum", "zur", "sich", "nicht", "auch");

    private QueryTerms() {
    }

    static Set<String> of(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String word : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() >= 3 && !STOPWORDS.contains(word)) terms.add(word);
        }
        return terms;
    }
}
