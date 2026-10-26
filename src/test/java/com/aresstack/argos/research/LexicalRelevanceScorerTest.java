package com.aresstack.argos.research;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LexicalRelevanceScorerTest {
    private final LexicalRelevanceScorer scorer = new LexicalRelevanceScorer();

    @Test
    void needsHalfTheDistinctTermsAndPrefersTitleMatches() {
        String query = "Wie funktioniert die Fingerprint-Rotation von Camoufox?";
        var titled = scorer.assess(query, "Camoufox fingerprint rotation\nbody");
        var body = scorer.assess(query, "Title\ncamoufox fingerprint rotation");
        var once = scorer.assess(query, "Title\ncamoufox only");
        assertTrue(titled.relevant());
        assertTrue(body.relevant());
        assertTrue(titled.score() > body.score());
        assertFalse(once.relevant());
    }

    @Test
    void keepsUnicodeWordsWhole() {
        assertTrue(QueryTerms.of("Hühner füttern").contains("hühner"));
        assertFalse(scorer.assess("Hühner füttern", "hner ttern").relevant());
    }
}
