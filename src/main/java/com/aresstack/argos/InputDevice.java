package com.aresstack.argos;

import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;

/**
 * The raw input channel: every call becomes one WebDriver BiDi {@code input.performActions} command (or, only for
 * {@link #insertText}, Playwright's script-based fallback). Separated from the planning so tests can record it.
 */
interface InputDevice {
    void move(double x, double y);

    default void down() {
        down(MouseButton.LEFT);
    }

    default void up() {
        up(MouseButton.LEFT);
    }

    void down(MouseButton button);

    void up(MouseButton button);

    void wheel(double deltaX, double deltaY);

    /** Throws when Playwright does not know the key (characters outside the US layout in Core 1.62). */
    void keyDown(String key);

    void keyUp(String key);

    /**
     * Page-visible with Playwright Core 1.62 ({@code window.__pw_bidiInsertText}); Playwright4J with
     * playwright4j#59 sends one real keyDown/keyUp per character instead.
     */
    void insertText(String text);

    void sleep(long millis);

    long now();

    static InputDevice of(Page page) {
        return new InputDevice() {
            @Override public void move(double x, double y) {
                page.mouse().move(x, y);
            }

            @Override public void down(MouseButton button) {
                page.mouse().down(new Mouse.DownOptions().setButton(button.playwright()));
            }

            @Override public void up(MouseButton button) {
                page.mouse().up(new Mouse.UpOptions().setButton(button.playwright()));
            }

            @Override public void wheel(double deltaX, double deltaY) {
                page.mouse().wheel(RealInput.wheelDelta(deltaX), RealInput.wheelDelta(deltaY));
            }

            @Override public void keyDown(String key) {
                page.keyboard().down(key);
            }

            @Override public void keyUp(String key) {
                page.keyboard().up(key);
            }

            @Override public void insertText(String text) {
                page.keyboard().insertText(text);
            }

            @Override public void sleep(long millis) {
                // waitForTimeout keeps processing Playwright events on the driver thread.
                if (millis > 0) page.waitForTimeout(millis);
            }

            @Override public long now() {
                return System.nanoTime() / 1_000_000;
            }
        };
    }
}
