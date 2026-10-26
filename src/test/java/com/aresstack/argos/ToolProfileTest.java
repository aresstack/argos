package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolProfileTest {
    @Test void defaultsToLegacyForBackwardsCompatibility() {
        assertEquals(ToolProfile.LEGACY, ToolProfile.parse(null));
        assertEquals(ToolProfile.LEGACY, ToolProfile.parse(" "));
    }

    @Test void parsesCaseInsensitively() {
        assertEquals(ToolProfile.COMPACT, ToolProfile.parse("compact"));
        assertEquals(ToolProfile.EXPERT, ToolProfile.parse(" Expert "));
    }

    @Test void rejectsUnknownProfiles() {
        assertThrows(IllegalArgumentException.class, () -> ToolProfile.parse("everything"));
    }
}
