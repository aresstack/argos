package com.aresstack.argos;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;

import java.util.random.RandomGenerator;

/**
 * Clicks and typing as genuine browser input: the element's bounding box is measured, the mouse is moved there
 * and pressed through WebDriver BiDi {@code input.performActions}. The page receives trusted events only; no
 * script runs in the page's own JavaScript realm and no synthetic {@code element.click()} is dispatched.
 */
final class RealInput {
    private static final double ACTION_TIMEOUT_MS = 10_000;
    private static final double HIT_CHECK_TIMEOUT_MS = 2_000;
    private final RandomGenerator random;

    RealInput(RandomGenerator random) {
        this.random = random;
    }

    /** Clicks a point inside the visible element, like a person would. */
    void click(Page page, Locator target) {
        // Trial run: Playwright's actionability checks (attached, visible, stable, enabled, not covered) and
        // scroll into view, evaluated in its isolated utility world. It does not click.
        target.click(new Locator.ClickOptions().setTrial(true).setTimeout(ACTION_TIMEOUT_MS));
        double[] point = verifiedPoint(target);
        page.mouse().move(point[0], point[1], new com.microsoft.playwright.Mouse.MoveOptions()
                .setSteps(6 + random.nextInt(10)));
        pause(page, 30, 140);
        page.mouse().down();
        pause(page, 40, 120);
        page.mouse().up();
    }

    /**
     * A random point inside the element that Playwright's hit-target check confirms: scrolled into the viewport
     * and not covered by another element. Falls back to the element's center, which the first trial validated.
     */
    private double[] verifiedPoint(Locator target) {
        for (int attempt = 0; attempt < 3; attempt++) {
            BoundingBox box = visibleBox(target);
            double[] point = pointInside(box);
            double offsetX = point[0] - box.x;
            double offsetY = point[1] - box.y;
            try {
                target.click(new Locator.ClickOptions().setTrial(true).setTimeout(HIT_CHECK_TIMEOUT_MS)
                        .setPosition(offsetX, offsetY));
            } catch (com.microsoft.playwright.PlaywrightException missed) {
                continue;
            }
            // The trial may have scrolled; the offset inside the element stays valid.
            BoundingBox now = visibleBox(target);
            return new double[] {now.x + offsetX, now.y + offsetY};
        }
        target.click(new Locator.ClickOptions().setTrial(true).setTimeout(ACTION_TIMEOUT_MS));
        BoundingBox box = visibleBox(target);
        return new double[] {box.x + box.width / 2, box.y + box.height / 2};
    }

    private static BoundingBox visibleBox(Locator target) {
        BoundingBox box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(ACTION_TIMEOUT_MS));
        if (box == null || box.width <= 0 || box.height <= 0) {
            throw new IllegalStateException("Element is not visible on the page");
        }
        return box;
    }

    /** Focuses the field by a real click, replaces its content and types {@code text} key by key. */
    void replaceText(Page page, Locator target, String text) {
        if (!target.isEditable(new Locator.IsEditableOptions().setTimeout(ACTION_TIMEOUT_MS))) {
            throw new IllegalStateException("Element is not an editable field");
        }
        click(page, target);
        page.keyboard().press("ControlOrMeta+A");
        page.keyboard().press("Backspace");
        type(page, text);
    }

    /** Types {@code text} with uneven delays. A trailing newline presses Enter. */
    void type(Page page, String text) {
        boolean submit = text.endsWith("\n");
        String content = submit ? text.substring(0, text.length() - 1) : text;
        // Long texts are typed faster so a tool call stays well below the browser timeout.
        int min = content.length() > 80 ? 4 : 35;
        int max = content.length() > 80 ? 15 : 110;
        content.codePoints().forEach(codePoint -> {
            page.keyboard().type(new String(Character.toChars(codePoint)));
            pause(page, min, max);
        });
        if (submit) page.keyboard().press("Enter");
    }

    /** A point in the inner half of the box, so borders and rounded corners are never hit. */
    double[] pointInside(BoundingBox box) {
        double x = box.x + box.width * (0.25 + random.nextDouble() * 0.5);
        double y = box.y + box.height * (0.25 + random.nextDouble() * 0.5);
        return new double[] {x, y};
    }

    private void pause(Page page, int minMillis, int maxMillis) {
        // waitForTimeout keeps processing Playwright events on the driver thread.
        page.waitForTimeout(minMillis + random.nextInt(Math.max(1, maxMillis - minMillis)));
    }
}
