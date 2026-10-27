package com.aresstack.argos;

import java.util.Locale;

/**
 * Which MCP tool set Argos publishes. Small local models get a short, single-purpose
 * {@link #COMPACT} set; capable models get {@link #EXPERT}. {@link #CONVENIENCE} is {@link #EXPERT} plus
 * research tabs for the web_* tools (results open in parallel tabs, web_tabs, web_close_tab) and is the
 * default. {@link #LEGACY} is the original nine browser_* tools for existing clients.
 */
public enum ToolProfile {
    LEGACY,
    COMPACT,
    EXPERT,
    CONVENIENCE;

    /** True for the profiles that publish the expert browser_* tools. */
    public boolean includesExpertTools() {
        return this == EXPERT || this == CONVENIENCE;
    }

    public static ToolProfile parse(String value) {
        if (value == null || value.isBlank()) return CONVENIENCE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "ARGOS_TOOL_PROFILE must be 'legacy', 'compact', 'expert' or 'convenience': " + value);
        }
    }
}
