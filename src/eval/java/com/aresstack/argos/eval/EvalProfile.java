package com.aresstack.argos.eval;

import com.aresstack.argos.ToolProfile;

import java.util.Locale;

/**
 * The published tool profiles plus one candidate: {@code compact-interact} is compact with web_controls,
 * web_click and web_fill, which join the compact profile only once small models handle them (analysis 2.2).
 */
public enum EvalProfile {
    LEGACY(ToolProfile.LEGACY, false),
    COMPACT(ToolProfile.COMPACT, false),
    COMPACT_INTERACT(ToolProfile.COMPACT, true),
    EXPERT(ToolProfile.EXPERT, false);

    final ToolProfile base;
    final boolean compactInteraction;

    EvalProfile(ToolProfile base, boolean compactInteraction) {
        this.base = base;
        this.compactInteraction = compactInteraction;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static EvalProfile parse(String value) {
        for (EvalProfile profile : values()) {
            if (profile.id().equals(value.trim().toLowerCase(Locale.ROOT))) return profile;
        }
        throw new IllegalArgumentException("eval.profile must be legacy, compact, compact-interact or expert: " + value);
    }
}
