package com.aresstack.argos.research;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResearchSettingsTest {
    @Test
    void isOffByDefault() {
        ResearchSettings settings = ResearchSettings.from(Map.of());
        assertFalse(settings.enabled());
        assertEquals(ResearchBudget.defaults(), settings.budget());
    }

    @Test
    void readsBudgetAndClampsIt() {
        ResearchSettings settings = ResearchSettings.from(Map.of("RESEARCH_ENABLED", "TRUE",
                "RESEARCH_MAX_PAGES", "500", "RESEARCH_MAX_SECONDS", "60",
                "RESEARCH_MIN_SITES", "9"));
        assertTrue(settings.enabled());
        assertEquals(50, settings.budget().maxPagesRead());
        assertEquals(Duration.ofSeconds(60), settings.budget().maxDuration());
        assertEquals(settings.budget().minimumSources(), settings.budget().minimumDistinctHosts());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> ResearchSettings.from(Map.of("RESEARCH_ENABLED", "yes")));
        assertThrows(IllegalArgumentException.class,
                () -> ResearchSettings.from(Map.of("RESEARCH_MAX_PAGES", "many")));
    }
}
