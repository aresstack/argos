package com.aresstack.argos;

import java.util.Locale;

/**
 * Which MCP tool set Argos publishes. Small local models get a short, single-purpose
 * {@link #COMPACT} set; capable models get {@link #EXPERT}. {@link #LEGACY} is the
 * original nine browser_* tools for existing clients. {@link #CONVENIENCE} is the research profile:
 * parallel search over all engines and id-based navigation in tabs (see docs/convenience-profile.md).
 * {@link #DEVELOPER}, the default, adds page, DOM and network diagnosis to it.
 */
public enum ToolProfile {
    LEGACY,
    COMPACT,
    EXPERT,
    CONVENIENCE,
    /** Convenience plus page, DOM and network diagnosis on the same tab ids. */
    DEVELOPER;

    public static ToolProfile parse(String value) {
        if (value == null || value.isBlank()) return DEVELOPER;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "TOOL_PROFILE must be 'developer', 'convenience', 'expert', 'compact' or 'legacy': " + value);
        }
    }

    /** Convenience and developer share the id-based research tools and their tab ids. */
    public boolean convenienceBased() {
        return this == CONVENIENCE || this == DEVELOPER;
    }
}
