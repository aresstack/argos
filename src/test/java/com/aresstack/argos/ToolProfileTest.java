package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolProfileTest {
    @Test void defaultsToConvenience() {
        assertEquals(ToolProfile.CONVENIENCE, ToolProfile.parse(null));
        assertEquals(ToolProfile.CONVENIENCE, ToolProfile.parse(" "));
        assertEquals(ToolProfile.CONVENIENCE, ToolProfile.parse("Convenience"));
        assertEquals(ToolProfile.CONVENIENCE, new ArgosConfig("127.0.0.1", 0, null, null, null, true, null,
                "none", null, "argos", null, null).toolProfile());
    }

    @Test void parsesCaseInsensitively() {
        assertEquals(ToolProfile.COMPACT, ToolProfile.parse("compact"));
        assertEquals(ToolProfile.EXPERT, ToolProfile.parse(" Expert "));
    }

    @Test void rejectsUnknownProfiles() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ToolProfile.parse("everything"));
        assertTrue(error.getMessage().contains("'convenience', 'expert', 'compact' or 'legacy'"), error.getMessage());
    }
}
