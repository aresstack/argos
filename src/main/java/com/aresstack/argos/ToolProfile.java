package com.aresstack.argos;

import java.util.Locale;

/**
 * Which MCP tool set Argos publishes. Small local models get a short, single-purpose
 * {@link #COMPACT} set; capable models get {@link #EXPERT}. {@link #LEGACY} is the
 * original nine browser_* tools for existing clients; {@link #EXPERT} is the default.
 */
public enum ToolProfile {
    LEGACY,
    COMPACT,
    EXPERT;

    public static ToolProfile parse(String value) {
        if (value == null || value.isBlank()) return EXPERT;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "ARGOS_TOOL_PROFILE must be 'legacy', 'compact' or 'expert': " + value);
        }
    }
}
