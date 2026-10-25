package com.aresstack.argos;

import java.util.Locale;

/** Which MCP tool set Argos publishes. LEGACY keeps the original nine browser_* tools. */
public enum ToolProfile {
    LEGACY, EXPERT;

    public static ToolProfile parse(String value) {
        if (value == null || value.isBlank()) return LEGACY;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("ARGOS_TOOL_PROFILE must be 'legacy' or 'expert': " + value);
        }
    }
}
