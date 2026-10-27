package com.aresstack.argos;

import java.util.Locale;

/** The mouse buttons a pointer tool can press. */
enum MouseButton {
    LEFT, MIDDLE, RIGHT;

    /** Playwright's button option. */
    com.microsoft.playwright.options.MouseButton playwright() {
        return com.microsoft.playwright.options.MouseButton.valueOf(name());
    }

    static MouseButton parse(String value) {
        if (value == null || value.isBlank()) return LEFT;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("button must be left, middle or right, not \"" + value + "\"");
        }
    }
}
