package com.aresstack.argos;

import java.util.Locale;

/**
 * Which MCP tool set Argos publishes. Small local models get a short, single-purpose
 * {@link #COMPACT} set; capable models get {@link #EXPERT}. {@link #LEGACY} is the
 * original nine browser_* tools for existing clients. {@link #CONVENIENCE}, the default, is the research profile:
 * parallel search over all engines and id-based navigation in tabs (see docs/convenience-profile.md).
 */
public enum ToolProfile {
    LEGACY,
    COMPACT,
    EXPERT,
    CONVENIENCE;

    public static ToolProfile parse(String value) {
        if (value == null || value.isBlank()) return CONVENIENCE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "ARGOS_TOOL_PROFILE must be 'convenience', 'expert', 'compact' or 'legacy': " + value);
        }
    }
}
