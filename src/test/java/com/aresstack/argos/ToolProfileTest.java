package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolProfileTest {
    @Test void defaultsToDeveloper() {
        assertEquals(ToolProfile.DEVELOPER, ToolProfile.parse(null));
        assertEquals(ToolProfile.DEVELOPER, ToolProfile.parse(""));
        assertEquals(ToolProfile.DEVELOPER, ToolProfile.parse(" "));
        assertEquals(ToolProfile.DEVELOPER, new ArgosConfig("127.0.0.1", 0, null, null, null, true, null,
                "none", null, "argos", null, null).toolProfile());
    }

    @Test void parsesCaseInsensitively() {
        assertEquals(ToolProfile.COMPACT, ToolProfile.parse("compact"));
        assertEquals(ToolProfile.EXPERT, ToolProfile.parse(" Expert "));
        for (ToolProfile profile : ToolProfile.values()) {
            assertEquals(profile, ToolProfile.parse(profile.name().toLowerCase(java.util.Locale.ROOT)));
        }
        assertEquals(ToolProfile.CONVENIENCE, ToolProfile.parse("Convenience"));
        assertEquals(5, ToolProfile.values().length);
        assertTrue(ToolProfile.DEVELOPER.convenienceBased());
        assertFalse(ToolProfile.EXPERT.convenienceBased());
    }

    @Test void rejectsUnknownProfiles() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ToolProfile.parse("everything"));
        assertTrue(error.getMessage().contains("'developer', 'convenience', 'expert', 'compact' or 'legacy'"), error.getMessage());
    }
}
