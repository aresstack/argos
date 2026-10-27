package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolProfileTest {
    @Test void defaultsToConvenience() {
        assertEquals(ToolProfile.CONVENIENCE, ToolProfile.parse(null));
        assertEquals(ToolProfile.CONVENIENCE, ToolProfile.parse(" "));
    }

    @Test void parsesCaseInsensitively() {
        assertEquals(ToolProfile.COMPACT, ToolProfile.parse("compact"));
        assertEquals(ToolProfile.EXPERT, ToolProfile.parse(" Expert "));
        assertEquals(ToolProfile.CONVENIENCE, ToolProfile.parse("Convenience"));
    }

    @Test void rejectsUnknownProfiles() {
        assertThrows(IllegalArgumentException.class, () -> ToolProfile.parse("everything"));
    }
}
