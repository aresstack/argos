package com.aresstack.argos;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.random.RandomGenerator;

/**
 * Clicks, scrolling and typing as genuine browser input: the element's bounding box is measured, the mouse wheel
 * brings a random point of it into view, {@code elementFromPoint} confirms it is not covered, the mouse travels there
 * on a human path and is pressed, keys go down and up one by one, all through WebDriver BiDi
 * {@code input.performActions}. The page receives trusted events only. {@link HumanMotion} and {@link HumanTyping}
 * decide where and when; this class only measures and plays.
 *
 * <p>Playwright's own actions (also trial clicks), {@code isEditable}, {@code inputValue}, {@code getAttribute} and
 * {@code scrollIntoView} dispatch {@code __playwright_mark_target__} events or scroll by script, which pages can
 * observe (playwright4j #38, #42). This class therefore uses only {@code boundingBox}, element handles with
 * transient read-only evaluations, and raw mouse and keyboard input, which leave no such trace.
 */
final class RealInput {
    private static final double ACTION_TIMEOUT_MS = 10_000;
    /** Pixels per wheel notch, as Firefox scrolls for one notch of a common mouse. */
    private static final int WHEEL_NOTCH = 100;
    private static final int MAX_WHEEL_BURSTS = 12;
    private static final double MARGIN = 4;
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

    private final HumanInputSettings settings;
    private final HumanMotion motion;
    private final HumanTyping typing;
    /** Where the pointer of each tab was left: BiDi keeps one input state per browsing context. */
    private final Map<Page, HumanMotion.Point> pointers = new WeakHashMap<>();
    private HumanMotion.Box viewport;
    /** Whether Playwright sends characters outside the US layout as real keys; null until first tried. */
    private Boolean unicodeKeys;

    RealInput(HumanInputSettings settings) {
        this.settings = settings;
        RandomGenerator random = settings.random();
        this.motion = new HumanMotion(random, settings);
        this.typing = new HumanTyping(random, settings);
    }

    /** The window's inner size from the Camoufox fingerprint, used for wheel scrolling and to keep paths on screen. */
    void viewport(double width, double height) {
        viewport = width > 0 && height > 0 ? new HumanMotion.Box(0, 0, width, height) : null;
    }

    /** Clicks a point inside the visible element, like a person would. */
    void click(Page page, Locator target) {
        ElementHandle element = target.elementHandle(new Locator.ElementHandleOptions().setTimeout(ACTION_TIMEOUT_MS));
        try {
            click(page, element);
        } finally {
            element.dispose();
        }
    }

    private void click(Page page, ElementHandle element) {
        InputDevice device = InputDevice.of(page);
        HumanMotion.Box box = stableBox(page, element);
        HumanMotion.Point point = reachablePoint(page, device, element, box);
        moveTo(page, device, point, Math.min(box.width(), box.height()));
        device.sleep(motion.hoverBeforePress());
        device.down();
        device.sleep(motion.buttonHold());
        device.up();
    }

    /** The element's box once it stops moving (animations, late layout), measured a few frames apart. */
    private static HumanMotion.Box stableBox(Page page, ElementHandle element) {
        HumanMotion.Box box = box(visibleBox(element));
        for (int attempt = 0; attempt < 10; attempt++) {
            page.waitForTimeout(50);
            HumanMotion.Box again = box(visibleBox(element));
            if (again.equals(box)) return box;
            box = again;
        }
        return box;
    }

    /**
     * A point inside the element, brought into the viewport by wheel input and confirmed with
     * {@code elementFromPoint}: three points scattered around the middle, then the center.
     */
    private HumanMotion.Point reachablePoint(Page page, InputDevice device, ElementHandle element, HumanMotion.Box box) {
        for (int attempt = 0; attempt < 4; attempt++) {
            HumanMotion.Point point = attempt < 3 ? motion.clickPoint(box) : rounded(box.center());
            HumanMotion.Point reached = scrollTo(page, device, element, point.x() - box.x(), point.y() - box.y());
            if (reached != null) return reached;
            box = box(visibleBox(element));
        }
        throw new IllegalStateException("Element is covered by another element (for example a cookie banner) "
                + "or cannot be scrolled into view");
    }

    /**
     * Flicks of the wheel until {@code box + offset} lies in the upper middle of the viewport; null if it stays
     * covered or unreachable. When the page does not move (the element is in a scrolling container), the pointer
     * first travels over the element's projection, as a person would do, so that container scrolls.
     */
    private HumanMotion.Point scrollTo(Page page, InputDevice device, ElementHandle element,
                                       double offsetX, double offsetY) {
        HumanMotion.Box view = viewport(page);
        HumanMotion.Point last = null;
        boolean overContainer = false;
        for (int burst = 0; burst < MAX_WHEEL_BURSTS; burst++) {
            BoundingBox box = visibleBox(element);
            HumanMotion.Point point = new HumanMotion.Point(Math.round(box.x + offsetX), Math.round(box.y + offsetY));
            boolean insideX = point.x() >= MARGIN && point.x() <= view.width() - MARGIN;
            boolean insideY = point.y() >= MARGIN && point.y() <= view.height() - MARGIN;
            if (insideX && insideY) return hits(page, element, point) ? point : null;
            if (point.equals(last)) {
                if (overContainer) return null;
                overContainer = true;
                moveTo(page, device, new HumanMotion.Point(
                        Math.round(Math.min(view.width() - 20, Math.max(20, point.x()))),
                        Math.round(Math.min(view.height() - 20, Math.max(20, point.y())))), 40);
            }
            last = point;
            if (!pointers.containsKey(page)) enter(page, device, view.center());
            double dy = insideY ? 0 : point.y() - motion.scrollAnchor(view.height());
            double dx = insideX ? 0 : point.x() - view.width() / 2;
            // Horizontal scrolling is rare for people; one notch-sized step per burst is enough.
            if (dx != 0) device.wheel(Math.round(Math.max(-WHEEL_NOTCH * 3, Math.min(WHEEL_NOTCH * 3, dx))), 0);
            if (dy != 0) {
                for (long[] notch : motion.wheelBurst(dy, WHEEL_NOTCH)) {
                    device.wheel(0, notch[0]);
                    device.sleep(notch[1]);
                }
            }
            device.sleep(motion.wheelPause());
        }
        return null;
    }

    /** Whether a click at {@code point} reaches the element: a transient, read-only main-world evaluation. */
    private static boolean hits(Page page, ElementHandle element, HumanMotion.Point point) {
        return Boolean.TRUE.equals(page.evaluate(HITS, List.of(element, point.x(), point.y())));
    }

    /**
     * Runs {@code function} on the element in the page's realm. An element handle is used because aria refs exist
     * only in Playwright's isolated world ({@code evaluateAll} would match nothing); creating and evaluating a
     * handle dispatches no marker events. With {@code extra}, the function receives {@code [element, ...extra]}.
     */
    static Object onElement(Locator target, String function, List<?> extra) {
        ElementHandle handle = target.elementHandle(new Locator.ElementHandleOptions().setTimeout(ACTION_TIMEOUT_MS));
        try {
            if (extra == null) return handle.evaluate(function);
            List<Object> arguments = new ArrayList<>();
            arguments.add(handle);
            arguments.addAll(extra);
            return target.page().evaluate(function, arguments);
        } finally {
            handle.dispose();
        }
    }

    private static HumanMotion.Point rounded(HumanMotion.Point point) {
        return new HumanMotion.Point(Math.round(point.x()), Math.round(point.y()));
    }

    /**
     * The window's inner size: from the Camoufox fingerprint, or else read once with a transient, read-only
     * evaluation (stealth tier 3) that leaves nothing behind in the page.
     */
    private HumanMotion.Box viewport(Page page) {
        if (viewport == null) {
            Object size = page.evaluate("() => [innerWidth, innerHeight]");
            if (size instanceof List<?> values && values.size() == 2
                    && values.get(0) instanceof Number width && values.get(1) instanceof Number height) {
                viewport(width.doubleValue(), height.doubleValue());
            }
            if (viewport == null) throw new IllegalStateException("Viewport size is unknown");
        }
        return viewport;
    }

    /**
     * Brings the pointer onto the page before its first movement in this tab: it enters across the top edge, as a
     * hand coming from the address bar would, and travels on from there; it never appears out of nowhere.
     */
    private void enter(Page page, InputDevice device, HumanMotion.Point target) {
        HumanMotion.Point start = motion.entryPoint(target, viewport);
        device.move(start.x(), start.y());
        pointers.put(page, start);
    }

    private void moveTo(Page page, InputDevice device, HumanMotion.Point point, double targetSize) {
        if (!pointers.containsKey(page)) enter(page, device, point);
        List<HumanMotion.Step> steps = motion.path(pointers.get(page), point, targetSize, viewport);
        long start = device.now();
        for (int i = 0; i < steps.size(); i++) {
            HumanMotion.Step step = steps.get(i);
            device.sleep(start + step.at() - device.now());
            try {
                device.move(step.x(), step.y());
            } catch (PlaywrightException outside) {
                // An arc may leave the real viewport near its edge; skip that sample, never the target itself.
                if (i == steps.size() - 1) throw outside;
            }
        }
        pointers.put(page, point);
    }

    private static BoundingBox visibleBox(ElementHandle element) {
        BoundingBox box = element.boundingBox();
        if (box == null || box.width <= 0 || box.height <= 0) {
            throw new IllegalStateException("Element is not visible on the page");
        }
        return box;
    }

    private static HumanMotion.Box box(BoundingBox box) {
        return new HumanMotion.Box(box.x, box.y, box.width, box.height);
    }

    /**
     * Focuses the field by a real click, replaces its content and types {@code text} key by key. Ctrl+A and
     * Backspace are always pressed: reading the current value would go through Playwright's marked element reads.
     */
    void replaceText(Page page, Locator target, String text) {
        rejectUntypeable(text);
        ElementHandle element = target.elementHandle(new Locator.ElementHandleOptions().setTimeout(ACTION_TIMEOUT_MS));
        try {
            if (!Boolean.TRUE.equals(element.evaluate(EDITABLE))) {
                throw new IllegalStateException("Element is not an editable field");
            }
            click(page, element);
        } finally {
            element.dispose();
        }
        InputDevice device = InputDevice.of(page);
        device.sleep(motion.reactionPause());
        playKeys(device, typing.clearField(), false);
        type(device, text);
    }

    /** Types {@code text} into the focused element. A trailing newline presses Enter. */
    void type(Page page, String text) {
        rejectUntypeable(text);
        type(InputDevice.of(page), text);
    }

    void type(InputDevice device, String text) {
        rejectUntypeable(text);
        playKeys(device, typing.type(text), true);
    }

    /** Known before the first key: this Playwright refuses non-US characters and the policy forbids the fallback. */
    private void rejectUntypeable(String text) {
        if (!Boolean.FALSE.equals(unicodeKeys) || settings.unicode() == HumanInputSettings.UnicodePolicy.INSERT) {
            return;
        }
        String checked = settings.unicode() == HumanInputSettings.UnicodePolicy.TRANSLITERATE
                ? HumanTyping.transliterate(text) : text;
        checked.codePoints().filter(c -> !HumanTyping.inLayout(c)).findFirst()
                .ifPresent(c -> { throw untypeable(c, false); });
    }

    private void playKeys(InputDevice device, List<HumanTyping.KeyEvent> events, boolean countText) {
        long start = device.now();
        Set<String> inserted = new HashSet<>();
        Set<String> pressed = new LinkedHashSet<>();
        int typed = 0;
        for (HumanTyping.KeyEvent event : events) {
            device.sleep(start + event.at() - device.now());
            String key = event.key();
            if (event.kind() == HumanTyping.Kind.UP) {
                if (inserted.remove(key)) continue;
                device.keyUp(key);
                pressed.remove(key);
                continue;
            }
            if (!event.layout() && Boolean.FALSE.equals(unicodeKeys)) {
                if (settings.unicode() != HumanInputSettings.UnicodePolicy.INSERT) {
                    throw takeBack(device, pressed, typed, key);
                }
                device.insertText(key);
                inserted.add(key);
                typed++;
                continue;
            }
            try {
                device.keyDown(key);
                if (!event.layout()) unicodeKeys = true;
            } catch (PlaywrightException refused) {
                if (event.layout() || !String.valueOf(refused.getMessage()).contains("Unknown key")) throw refused;
                unicodeKeys = false;
                if (settings.unicode() != HumanInputSettings.UnicodePolicy.INSERT) {
                    throw takeBack(device, pressed, typed, key);
                }
                device.insertText(key);
                inserted.add(key);
                typed++;
                continue;
            }
            pressed.add(key);
            if (!countText) continue;
            if ("Backspace".equals(key)) typed = Math.max(0, typed - 1);
            else if (key.codePointCount(0, key.length()) == 1) typed++;
        }
    }

    /** Releases held keys and erases what was typed, so a refused character leaves no half-typed text behind. */
    private IllegalStateException takeBack(InputDevice device, Set<String> pressed, int typed, String key) {
        for (String held : new ArrayList<>(pressed)) device.keyUp(held);
        playKeys(device, typing.erase(typed), false);
        return untypeable(key.codePointAt(0), typed > 0);
    }

    private static IllegalStateException untypeable(int codePoint, boolean erased) {
        return new IllegalStateException("Cannot type '" + new String(Character.toChars(codePoint))
                + "' as a real key: Playwright Core sends characters outside the US layout only through a page"
                + " script (playwright4j#36)." + (erased ? " The text typed so far was erased." : "")
                + " Set ARGOS_INPUT_UNICODE=transliterate (ä -> ae) or insert (visible to the page).");
    }
}
