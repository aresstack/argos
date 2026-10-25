package com.aresstack.argos;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Clicks and typing as genuine browser input: the element's bounding box is measured, the page is scrolled with
 * real mouse-wheel input until a random point of the element is visible and not covered, and the mouse is moved
 * there and pressed through WebDriver BiDi {@code input.performActions}. The page receives trusted events only.
 *
 * <p>Playwright's own actions (also trial clicks), {@code isEditable}, {@code getAttribute} and
 * {@code scrollIntoView} dispatch {@code __playwright_mark_target__} events or scroll by script, which pages can
 * observe (playwright4j #38, #42). This class therefore uses only {@code boundingBox}, element handles and raw
 * mouse and keyboard input, which leave no such trace.
 */
final class RealInput {
    private static final double ACTION_TIMEOUT_MS = 10_000;
    private static final double MARGIN = 4;
    private static final int MAX_SCROLL_STEPS = 15;
    private static final String HITS = """
            ([element, x, y]) => {
              if (element.ownerDocument.defaultView !== window.top) return true;
              const hit = document.elementFromPoint(x, y);
              return !!hit && (hit === element || element.contains(hit) || hit.control === element);
            }""";
    private static final String EDITABLE = """
            element => element.isContentEditable
              || (/^(INPUT|TEXTAREA)$/.test(element.tagName) && !element.disabled && !element.readOnly
                  && !/^(button|checkbox|radio|submit|reset|file|image|range|color|hidden)$/i.test(element.type || ''))""";
    private final RandomGenerator random;

    RealInput(RandomGenerator random) {
        this.random = random;
    }

    /** Clicks a point inside the visible element, like a person would. */
    void click(Page page, Locator target) {
        double[] point = reachablePoint(page, target);
        page.mouse().move(point[0], point[1], new com.microsoft.playwright.Mouse.MoveOptions()
                .setSteps(6 + random.nextInt(10)));
        pause(page, 30, 140);
        page.mouse().down();
        pause(page, 40, 120);
        page.mouse().up();
    }

    /**
     * A point inside the element, scrolled into the viewport by wheel input and confirmed with
     * {@code elementFromPoint}: three random points in the inner half, then the center.
     */
    private double[] reachablePoint(Page page, Locator target) {
        for (int attempt = 0; attempt < 4; attempt++) {
            BoundingBox box = visibleBox(target);
            double[] point = attempt < 3 ? pointInside(box) : new double[] {box.x + box.width / 2, box.y + box.height / 2};
            double[] reached = scrollTo(page, target, point[0] - box.x, point[1] - box.y);
            if (reached != null) return reached;
        }
        throw new IllegalStateException("Element is covered by another element (for example a cookie banner) "
                + "or cannot be scrolled into view");
    }

    /** Wheels until {@code box + offset} lies inside the viewport; null if it stays covered or unreachable. */
    private double[] scrollTo(Page page, Locator target, double offsetX, double offsetY) {
        double[] lastPosition = null;
        for (int step = 0; step < MAX_SCROLL_STEPS; step++) {
            BoundingBox box = visibleBox(target);
            double x = box.x + offsetX;
            double y = box.y + offsetY;
            double[] viewport = viewport(page);
            double dx = x < MARGIN ? x - viewport[0] * 0.4 : x > viewport[0] - MARGIN ? x - viewport[0] * 0.6 : 0;
            double dy = y < MARGIN ? y - viewport[1] * 0.4 : y > viewport[1] - MARGIN ? y - viewport[1] * 0.6 : 0;
            if (dx == 0 && dy == 0) {
                return hits(target, x, y) ? new double[] {x, y} : null;
            }
            if (lastPosition != null && lastPosition[0] == x && lastPosition[1] == y) return null;
            lastPosition = new double[] {x, y};
            // Wheel over the element's projection so its own scroll container scrolls, like a person would.
            page.mouse().move(clamp(x, 10, viewport[0] - 10), clamp(y, 10, viewport[1] - 10),
                    new com.microsoft.playwright.Mouse.MoveOptions().setSteps(3 + random.nextInt(4)));
            // BiDi wheel deltas must be integers.
            page.mouse().wheel(Math.round(clamp(dx, -700, 700)), Math.round(clamp(dy, -700, 700)));
            pause(page, 120, 260);
        }
        return null;
    }

    private static boolean hits(Locator target, double x, double y) {
        return Boolean.TRUE.equals(onElement(target, HITS, List.of(x, y)));
    }

    /**
     * Runs {@code function} on the element in the page's realm. An element handle is used because aria refs exist
     * only in Playwright's isolated world ({@code evaluateAll} would match nothing); creating and evaluating a
     * handle dispatches no marker events. With {@code extra}, the function receives {@code [element, ...extra]}.
     */
    static Object onElement(Locator target, String function, List<?> extra) {
        com.microsoft.playwright.ElementHandle handle = target.elementHandle(
                new Locator.ElementHandleOptions().setTimeout(ACTION_TIMEOUT_MS));
        try {
            if (extra == null) return handle.evaluate(function);
            List<Object> arguments = new java.util.ArrayList<>();
            arguments.add(handle);
            arguments.addAll(extra);
            return target.page().evaluate(function, arguments);
        } finally {
            handle.dispose();
        }
    }

    private static double[] viewport(Page page) {
        // Camoufox runs without viewport emulation (setViewportSize(null)), so ask the window itself.
        Object size = page.evaluate("() => [innerWidth, innerHeight]");
        List<?> values = (List<?>) size;
        return new double[] {((Number) values.get(0)).doubleValue(), ((Number) values.get(1)).doubleValue()};
    }

    private static BoundingBox visibleBox(Locator target) {
        BoundingBox box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(ACTION_TIMEOUT_MS));
        if (box == null || box.width <= 0 || box.height <= 0) {
            throw new IllegalStateException("Element is not visible on the page");
        }
        return box;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Focuses the field by a real click, replaces its content and types {@code text} key by key. */
    void replaceText(Page page, Locator target, String text) {
        if (!Boolean.TRUE.equals(onElement(target, EDITABLE, null))) {
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
