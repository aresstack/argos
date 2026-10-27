package com.aresstack.argos;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;

import java.util.ArrayList;
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
    /**
     * "multi" for text areas and editable content, "single" for one-line inputs, "segmented:&lt;type&gt;" for date
     * and time inputs (no text selection, Ctrl+A there selects the whole page), "" when not editable.
     */
    private static final String EDITABLE = """
            element => element.isContentEditable || (element.tagName === 'TEXTAREA' && !element.disabled
                  && !element.readOnly) ? 'multi'
              : element.tagName === 'INPUT' && !element.disabled && !element.readOnly
                  && /^(date|time|datetime-local|month|week)$/i.test(element.type || '') ? 'segmented:' + element.type.toLowerCase()
              : element.tagName === 'INPUT' && !element.disabled && !element.readOnly
                  && !/^(button|checkbox|radio|submit|reset|file|image|range|color|hidden)$/i.test(element.type || '')
                ? 'single' : ''""";
    /** Whether keys now reach the element (or, for editable content, a node inside it): transient and read-only. */
    private static final String VALUE = "element => element.value";
    private static final String FOCUSED = """
            element => {
              let active = element.ownerDocument.activeElement;
              while (active && active.shadowRoot && active.shadowRoot.activeElement) active = active.shadowRoot.activeElement;
              return active === element || (element.isContentEditable && element.contains(active));
            }""";
    /** Our own probe page, never a website: records what its text area receives in the title. */
    private static final String PROBE_PAGE = """
            <html><head><title></title></head><body><textarea autofocus></textarea><script>
              const seen = [];
              for (const type of ['keydown', 'keyup', 'input'])
                document.querySelector('textarea').addEventListener(type,
                  e => { seen.push(type + ':' + (e.key || e.data) + ':' + e.isTrusted); document.title = seen.join(' '); });
            </script></body></html>""";

    /** How characters outside the US layout reach the page as real keys, if at all. */
    enum UnicodeKeys {
        /** Not probed yet: such text is refused before the first key, fail closed. */
        UNKNOWN,
        /** {@code keyboard.down("ä")} sends a real key. */
        KEY_NAMES,
        /**
         * {@code keyboard.insertText("ä")} sends a real keyDown/keyUp through WebDriver BiDi instead of Playwright's
         * page script (Playwright4J with aresstack/playwright4j#59); key names are still refused.
         */
        TEXT_AS_KEYS,
        /** Only the page-visible script fallback exists (Playwright Core 1.62, playwright4j#36). */
        NONE
    }

    private final HumanInputSettings settings;
    private final HumanMotion motion;
    private final HumanTyping typing;
    /** Where the pointer of each tab was left: BiDi keeps one input state per browsing context. */
    private final Map<Page, HumanMotion.Point> pointers = new WeakHashMap<>();
    private HumanMotion.Box viewport;
    /** Whether and how Playwright sends characters outside the US layout as real keys; probed at browser start. */
    private UnicodeKeys unicodeKeys = UnicodeKeys.UNKNOWN;

    RealInput(HumanInputSettings settings) {
        this.settings = settings;
        RandomGenerator random = settings.random();
        this.motion = new HumanMotion(random, settings);
        this.typing = new HumanTyping(random, settings);
        if (settings.unicode() == HumanInputSettings.UnicodePolicy.INSERT) {
            System.err.println("ARGOS_INPUT_UNICODE=insert is a debug/compatibility mode, not stealth input: characters"
                    + " outside the US layout reach the page as untrusted, script-made input events.");
        }
    }

    /**
     * Finds out once, before any website is loaded, whether this Playwright sends {@code ä} as a real key. Playwright
     * Core 1.62 refuses the key name before anything reaches the browser (playwright4j#36); a version that accepts it
     * sends a genuine keyDown/keyUp to the blank page, where it has no effect. Otherwise our own probe page (no
     * website, nothing of it survives) checks whether {@code insertText} arrives as trusted key events
     * (playwright4j#59) or only as the script-made input event. The tab is left blank again.
     */
    void probeUnicode(Page blank) {
        try {
            blank.keyboard().down("ä");
            blank.keyboard().up("ä");
            unicodeKeys = UnicodeKeys.KEY_NAMES;
            return;
        } catch (PlaywrightException refused) {
            unicodeKeys = UnicodeKeys.NONE;
        }
        try {
            blank.setContent(PROBE_PAGE);
            blank.focus("textarea");
            blank.keyboard().insertText("ä");
            String seen = blank.title();
            if (seen.contains("keydown:ä:true") && seen.contains("keyup:ä:true") && seen.contains("input:ä:true")
                    && !seen.contains(":false")) {
                unicodeKeys = UnicodeKeys.TEXT_AS_KEYS;
            }
        } catch (PlaywrightException failed) {
            unicodeKeys = UnicodeKeys.NONE;
        } finally {
            blank.navigate("about:blank");
        }
    }

    UnicodeKeys unicodeKeys() {
        return unicodeKeys;
    }

    /** For tests: the outcome of {@link #probeUnicode}. */
    void unicodeKeys(UnicodeKeys probed) {
        unicodeKeys = probed;
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
        pointAt(page, device, element);
        press(device, MouseButton.LEFT);
    }

    private HumanMotion.Point pointAt(Page page, InputDevice device, ElementHandle element) {
        HumanMotion.Box box = stableBox(page, element);
        HumanMotion.Point point = reachablePoint(page, device, element, box);
        moveTo(page, device, point, Math.min(box.width(), box.height()));
        return point;
    }

    private void press(InputDevice device, MouseButton button) {
        device.sleep(motion.hoverBeforePress());
        device.down(button);
        device.sleep(motion.buttonHold());
        device.up(button);
    }

    // ---- Human pointer paths for drag and drop: HumanMotion paths, nothing is pressed. ----------------------

    /** Rests the pointer on a visible point of the element, scrolled into view by wheel input, on a human path. */
    HumanMotion.Point humanHover(Page page, Locator target) {
        ElementHandle element = target.elementHandle(new Locator.ElementHandleOptions().setTimeout(ACTION_TIMEOUT_MS));
        try {
            return pointAt(page, InputDevice.of(page), element);
        } finally {
            element.dispose();
        }
    }

    /** Moves the pointer on a human path to a viewport point given in CSS pixels. */
    HumanMotion.Point humanMove(Page page, double x, double y) {
        HumanMotion.Point point = viewportPoint(x, y, viewport(page));
        moveTo(page, InputDevice.of(page), point, 20);
        return point;
    }

    // ---- Plain pointer tools: one native mouse command per step, no HumanMotion paths or pauses. ----------

    /** Moves the pointer straight to a viewport point given in CSS pixels; nothing is pressed. */
    HumanMotion.Point pointerMove(Page page, double x, double y) {
        return pointerMove(page, InputDevice.of(page), viewportPoint(x, y, viewport(page)));
    }

    /** Moves straight to a viewport point given in CSS pixels and presses {@code button} there once. */
    HumanMotion.Point pointerClick(Page page, double x, double y, MouseButton button) {
        InputDevice device = InputDevice.of(page);
        HumanMotion.Point point = pointerMove(page, device, viewportPoint(x, y, viewport(page)));
        device.down(button);
        device.up(button);
        return point;
    }

    /**
     * Moves the pointer straight onto the center of the element located by {@code target}, measured with
     * {@code Locator.boundingBox}; nothing is pressed. An element outside the viewport is first brought in with one
     * native wheel turn. {@code Locator.hover} is not used: it scrolls by script and dispatches
     * {@code __playwright_mark_target__} events the page can observe (playwright4j #38).
     */
    HumanMotion.Point pointerHover(Page page, Locator target) {
        InputDevice device = InputDevice.of(page);
        HumanMotion.Box view = viewport(page);
        HumanMotion.Point center = center(target);
        if (!inside(center, view)) {
            if (!pointers.containsKey(page)) pointerMove(page, device, viewportPoint(view.width() / 2, view.height() / 2, view));
            device.wheel(wheelDelta(inside(center.x(), view.width()) ? 0 : center.x() - view.width() / 2),
                    wheelDelta(inside(center.y(), view.height()) ? 0 : center.y() - view.height() / 2));
            page.waitForTimeout(150);
            center = center(target);
            if (!inside(center, view)) throw new IllegalStateException("Element cannot be scrolled into view");
        }
        return pointerMove(page, device, viewportPoint(center.x(), center.y(), view));
    }

    /** One native wheel event of {@code deltaX}/{@code deltaY} CSS pixels where the pointer is (its center if unset). */
    void pointerWheel(Page page, double deltaX, double deltaY) {
        InputDevice device = InputDevice.of(page);
        if (!pointers.containsKey(page)) {
            HumanMotion.Box view = viewport(page);
            pointerMove(page, device, viewportPoint(view.width() / 2, view.height() / 2, view));
        }
        device.wheel(wheelDelta(deltaX), wheelDelta(deltaY));
    }

    /** WebDriver BiDi wheel actions take whole CSS pixels only; a fractional delta is rejected as invalid. */
    static long wheelDelta(double delta) {
        return Double.isFinite(delta) ? Math.round(delta) : 0;
    }

    private HumanMotion.Point pointerMove(Page page, InputDevice device, HumanMotion.Point point) {
        device.move(point.x(), point.y());
        pointers.put(page, point); // later human paths start where this pointer is
        return point;
    }

    private static HumanMotion.Point center(Locator target) {
        BoundingBox box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(ACTION_TIMEOUT_MS));
        if (box == null || box.width <= 0 || box.height <= 0) {
            throw new IllegalStateException("Element is not visible on the page");
        }
        return new HumanMotion.Point(box.x + box.width / 2, box.y + box.height / 2);
    }

    private static boolean inside(HumanMotion.Point point, HumanMotion.Box view) {
        return inside(point.x(), view.width()) && inside(point.y(), view.height());
    }

    private static boolean inside(double value, double size) {
        return value >= 0 && value < size;
    }

    /**
     * Validates a viewport point in CSS pixels and makes it a whole pixel. Flooring keeps every accepted value
     * inside: 0 <= x < width gives 0 <= floor(x) <= width - 1, where rounding could reach the edge itself.
     */
    static HumanMotion.Point viewportPoint(double x, double y, HumanMotion.Box view) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !inside(x, view.width()) || !inside(y, view.height())) {
            throw new IllegalArgumentException(String.format(java.util.Locale.ROOT, "Point (%s, %s) lies outside the"
                    + " viewport; x must be at least 0 and below %s, y at least 0 and below %s CSS pixels.", x, y,
                    view.width(), view.height()));
        }
        return new HumanMotion.Point(Math.floor(x), Math.floor(y));
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
     * The window's inner size as the page sees it now, read with a transient, read-only evaluation (stealth tier 3)
     * that leaves nothing behind; the same measurement {@code browser_screenshot} reports. The Camoufox fingerprint
     * size is only a fallback: the real window can differ from it (resized window, display scaling), and a stale
     * size rejected valid screenshot coordinates and stopped wheel scrolling short of the real lower edge.
     */
    private HumanMotion.Box viewport(Page page) {
        Object size;
        try {
            size = page.evaluate("() => [innerWidth, innerHeight]");
        } catch (PlaywrightException unreadable) {
            size = null;
        }
        if (size instanceof List<?> values && values.size() == 2
                && values.get(0) instanceof Number width && values.get(1) instanceof Number height
                && width.doubleValue() > 0 && height.doubleValue() > 0) {
            return new HumanMotion.Box(0, 0, width.doubleValue(), height.doubleValue());
        }
        if (viewport == null) throw new IllegalStateException("Viewport size is unknown");
        return viewport;
    }

    /**
     * Brings the pointer onto the page before its first movement in this tab: it enters across the top edge, as a
     * hand coming from the address bar would, and travels on from there; it never appears out of nowhere.
     */
    private void enter(Page page, InputDevice device, HumanMotion.Point target) {
        HumanMotion.Point start = motion.entryPoint(target, viewport(page));
        device.move(start.x(), start.y());
        pointers.put(page, start);
    }

    private void moveTo(Page page, InputDevice device, HumanMotion.Point point, double targetSize) {
        if (!pointers.containsKey(page)) enter(page, device, point);
        List<HumanMotion.Step> steps = motion.path(pointers.get(page), point, targetSize, viewport(page));
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
     * Ctrl+A is only pressed once the field holds the focus; otherwise it would select the whole page.
     */
    void replaceText(Page page, Locator target, String text) {
        text = lineEnds(text);
        rejectUntypeable(text);
        ElementHandle element = target.elementHandle(new Locator.ElementHandleOptions().setTimeout(ACTION_TIMEOUT_MS));
        try {
            Object kind = element.evaluate(EDITABLE);
            if (kind instanceof String k && k.startsWith("segmented:")) {
                fillSegments(page, element, k.substring("segmented:".length()), text);
                return;
            }
            checkFillable(kind, text);
            click(page, element);
            if (!Boolean.TRUE.equals(element.evaluate(FOCUSED))) {
                throw new IllegalStateException("The click did not put the focus into the field (an overlay or label"
                        + " may have taken it). Nothing was typed and no select-all was pressed.");
            }
            InputDevice device = InputDevice.of(page);
            device.sleep(motion.reactionPause());
            playKeys(device, typing.clearField(), false);
        } finally {
            element.dispose();
        }
        InputDevice device = InputDevice.of(page);
        type(device, text);
    }

    /**
     * Fills a native date or time input without any select-all: a real click on its first segment, then the digits
     * of each segment. Typing into a segment replaces it and moves on by itself. The segment order and the 12/24 hour
     * display depend on the browser locale, so the candidates are tried in turn and checked against the value.
     */
    private void fillSegments(Page page, ElementHandle element, String type, String text) {
        List<String> candidates = segmentKeys(type, text.strip());
        InputDevice device = InputDevice.of(page);
        String expected = text.strip();
        for (String keys : candidates) {
            clickFirstSegment(page, device, element);
            if (!Boolean.TRUE.equals(element.evaluate(FOCUSED))) {
                throw new IllegalStateException("The click did not put the focus into the " + type + " field."
                        + " Nothing was typed.");
            }
            device.sleep(motion.reactionPause());
            playKeys(device, typing.type(keys), false);
            if (expected.equals(element.evaluate(VALUE))) return;
        }
        throw new IllegalStateException("The " + type + " field did not take \"" + expected + "\" (it now holds \""
                + element.evaluate(VALUE) + "\"). Its segments may follow another order in this browser locale.");
    }

    private void clickFirstSegment(Page page, InputDevice device, ElementHandle element) {
        HumanMotion.Box field = stableBox(page, element);
        // The first segment sits at the left, after the padding; aim at a narrow strip there, never the centre.
        HumanMotion.Box first = new HumanMotion.Box(field.x() + Math.min(4, field.width() / 8), field.y(),
                Math.max(1, Math.min(12, field.width() / 6)), field.height());
        HumanMotion.Point point = reachablePoint(page, device, element, first);
        moveTo(page, device, point, Math.min(first.width(), first.height()));
        press(device, MouseButton.LEFT);
    }

    /**
     * The key sequences that may put {@code value} into a native {@code type} input, most likely first. Values use
     * the HTML formats: time {@code HH:MM}, date {@code YYYY-MM-DD}. Other inputs and malformed values are refused
     * before any input.
     */
    static List<String> segmentKeys(String type, String value) {
        switch (type) {
            case "time" -> {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2}):(\\d{2})").matcher(value);
                if (!m.matches() || Integer.parseInt(m.group(1)) > 23 || Integer.parseInt(m.group(2)) > 59) {
                    throw new IllegalArgumentException("A time field takes HH:MM in 24 hours, e.g. 18:30.");
                }
                int hour = Integer.parseInt(m.group(1));
                String twelve = String.format("%02d", hour % 12 == 0 ? 12 : hour % 12);
                return List.of(m.group(1) + m.group(2),
                        twelve + m.group(2) + (hour < 12 ? "A" : "P"));
            }
            case "date" -> {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(value);
                if (!m.matches()) throw new IllegalArgumentException("A date field takes YYYY-MM-DD, e.g. 2026-10-27.");
                String y = m.group(1), mo = m.group(2), d = m.group(3);
                return List.of(mo + d + y, d + mo + y, y + mo + d);
            }
            default -> throw new IllegalStateException("A " + type + " field cannot be filled yet: select-all would"
                    + " mark the whole page. Nothing was typed.");
        }
    }

    /** Refuses, before any input, fields that {@link #replaceText} cannot clear and fill safely. */
    static void checkFillable(Object kind, String text) {
        if (!"single".equals(kind) && !"multi".equals(kind)) {
            throw new IllegalStateException("Element is not an editable field");
        }
        if ("single".equals(kind) && lineBreakInside(text)) {
            throw new IllegalArgumentException("A single-line field takes no line break inside the text: the"
                    + " Enter key would submit the form halfway. Only a trailing newline presses Enter.");
        }
    }

    /** Types {@code text} into the focused element. A trailing newline presses Enter. */
    void type(Page page, String text) {
        text = lineEnds(text);
        rejectUntypeable(text);
        type(InputDevice.of(page), text);
    }

    void type(InputDevice device, String text) {
        text = lineEnds(text);
        rejectUntypeable(text);
        playKeys(device, typing.type(text), true);
    }

    /** Modifier keys as Playwright names them; ControlOrMeta is Cmd on macOS, Ctrl elsewhere. */
    static final List<String> MODIFIERS = List.of("Shift", "Control", "Alt", "Meta", "ControlOrMeta");
    /** Named keys a page may receive; other names are refused before any key goes down. */
    static final Set<String> NAMED_KEYS = Set.copyOf(List.of("Enter", "Tab", "Escape", "Backspace", "Delete",
            "Insert", "Home", "End", "PageUp", "PageDown", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Space",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "ContextMenu"));
    /** Shortcuts that would close or replace the browser window or tab Argos is working in. */
    private static final Set<String> BROWSER_SHORTCUTS = Set.of("Control+w", "Control+q", "Control+n", "Control+t",
            "Control+F4", "Alt+F4", "Meta+w", "Meta+q", "Meta+n", "Meta+t", "ControlOrMeta+w", "ControlOrMeta+q",
            "ControlOrMeta+n", "ControlOrMeta+t", "Control+Shift+w", "Control+Shift+q", "Meta+Shift+w");

    /** A parsed key combination such as {@code Control+Shift+ArrowLeft}. */
    record KeyChord(List<String> modifiers, String key, boolean layout) {
        @Override public String toString() {
            return modifiers.isEmpty() ? key : String.join("+", modifiers) + "+" + key;
        }
    }

    /**
     * Parses {@code Enter}, {@code Control+a}, {@code Shift+Tab} or a single character. Everything is checked before
     * the first key goes down: unknown names, a modifier on its own, reserved code points and, in strict mode,
     * characters this Playwright cannot send as a real key. There is no script fallback: a key is a key.
     */
    KeyChord chord(String keys) {
        if (keys == null || keys.isBlank()) throw new IllegalArgumentException("Name a key, e.g. Enter or Control+a");
        String trimmed = keys.strip();
        // "+" itself and "Control++" mean the plus key.
        boolean plusKey = trimmed.equals("+") || trimmed.endsWith("++");
        String prefix = plusKey ? trimmed.substring(0, Math.max(0, trimmed.length() - 2)) : trimmed;
        List<String> parts = prefix.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(prefix.split("\\+", -1)));
        if (plusKey) parts.add("+");
        String key = parts.remove(parts.size() - 1);
        List<String> modifiers = new ArrayList<>();
        for (String part : parts) {
            String modifier = MODIFIERS.stream().filter(m -> m.equalsIgnoreCase(alias(part.strip()))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("'" + part + "' in '" + keys + "' is not a "
                            + "modifier. Use Shift, Control, Alt, Meta or ControlOrMeta before the key, e.g. Control+a."));
            if (modifiers.contains(modifier)) throw new IllegalArgumentException(modifier + " appears twice in " + keys);
            modifiers.add(modifier);
        }
        String named = NAMED_KEYS.stream().filter(n -> n.equalsIgnoreCase(alias(key))).findFirst().orElse(null);
        if (named != null) {
            KeyChord chord = new KeyChord(List.copyOf(modifiers), "Space".equals(named) ? " " : named, true);
            refuseBrowserShortcut(chord);
            return chord;
        }
        if (MODIFIERS.stream().anyMatch(m -> m.equalsIgnoreCase(alias(key)))) {
            throw new IllegalArgumentException("'" + keys + "' ends with a modifier. Name the key to press with it, "
                    + "e.g. Control+a or Shift+Tab.");
        }
        if (key.codePointCount(0, key.length()) != 1) {
            throw new IllegalArgumentException("Unknown key '" + key + "'. Use one character (a, 1, ä) or a key name: "
                    + String.join(", ", new java.util.TreeSet<>(NAMED_KEYS)) + ". To type text, use fill.");
        }
        int codePoint = key.codePointAt(0);
        if (codePoint >= 0xE000 && codePoint <= 0xE05D || Character.isISOControl(codePoint)) {
            throw new IllegalArgumentException(String.format("Cannot press U+%04X as a key. Nothing was pressed.",
                    codePoint));
        }
        boolean layout = HumanTyping.inLayout(codePoint);
        // Only KEY_NAMES sends such a character as a real keyDown/keyUp that modifiers can combine with;
        // insertText (TEXT_AS_KEYS or the insert policy) is text, not a key.
        if (!layout && unicodeKeys != UnicodeKeys.KEY_NAMES) {
            throw new IllegalStateException("Cannot press '" + key + "' as a real key: this Playwright sends "
                    + "characters outside the US layout only as inserted text. Nothing was pressed.");
        }
        KeyChord chord = new KeyChord(List.copyOf(modifiers), key, layout);
        refuseBrowserShortcut(chord);
        return chord;
    }

    private static String alias(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "ctrl", "strg" -> "Control";
            case "cmd", "command", "win", "super" -> "Meta";
            case "option" -> "Alt";
            case "esc" -> "Escape";
            case "return" -> "Enter";
            case "del", "entf" -> "Delete";
            case "up" -> "ArrowUp";
            case "down" -> "ArrowDown";
            case "left" -> "ArrowLeft";
            case "right" -> "ArrowRight";
            case "pgup" -> "PageUp";
            case "pgdn", "pgdown" -> "PageDown";
            default -> name;
        };
    }

    private static void refuseBrowserShortcut(KeyChord chord) {
        if (chord.modifiers().isEmpty()) return;
        String shortcut = String.join("+", chord.modifiers()) + "+"
                + (chord.key().length() == 1 ? chord.key().toLowerCase(java.util.Locale.ROOT) : chord.key());
        if (BROWSER_SHORTCUTS.contains(shortcut)) {
            throw new IllegalArgumentException(chord + " would close or replace the browser window or tab. Use the "
                    + "tab tools instead. Nothing was pressed.");
        }
    }

    /**
     * Presses a key or shortcut {@code times} times, as real keyDown/keyUp events. With a {@code target} the element
     * is focused by a real click first; without one the keys go to whatever has the focus. The combination is fully
     * validated before the click.
     */
    KeyChord press(Page page, Locator target, String keys, int times) {
        KeyChord chord = chord(keys);
        if (times < 1 || times > 20) throw new IllegalArgumentException("times must be between 1 and 20");
        if (target != null) {
            click(page, target);
            InputDevice.of(page).sleep(motion.reactionPause());
        }
        press(InputDevice.of(page), chord, times);
        return chord;
    }

    void press(InputDevice device, KeyChord chord, int times) {
        Set<String> held = new LinkedHashSet<>();
        long start = device.now();
        try {
            for (HumanTyping.KeyEvent event : typing.chord(chord.modifiers(), chord.key(), chord.layout(), times)) {
                device.sleep(start + event.at() - device.now());
                if (event.kind() == HumanTyping.Kind.DOWN) {
                    device.keyDown(event.key());
                    held.add(event.key());
                } else {
                    device.keyUp(event.key());
                    held.remove(event.key());
                }
            }
        } catch (RuntimeException failed) {
            // Never leave a modifier held down: the next typed text would become shortcuts.
            List<String> reverse = new ArrayList<>(held);
            java.util.Collections.reverse(reverse);
            for (String key : reverse) {
                try {
                    device.keyUp(key);
                } catch (RuntimeException ignored) {
                    // keep releasing the others
                }
            }
            throw failed;
        }
    }

    /**
     * Validates the whole text before the field is focused, cleared or typed into: characters this Playwright cannot
     * send as real keys are refused unless the policy explicitly allows the page-visible fallback.
     */
    private void rejectUntypeable(String text) {
        // WebDriver reserves U+E000..U+E05D for named keys: as a key value U+E007 would press Enter.
        text.codePoints().filter(c -> c >= 0xE000 && c <= 0xE05D).findFirst().ifPresent(c -> {
            throw new IllegalArgumentException(String.format("Cannot type U+%04X: WebDriver reserves it for a named"
                    + " key. Nothing was typed into the field.", c));
        });
        if (settings.unicode() == HumanInputSettings.UnicodePolicy.INSERT) return;
        if (text.indexOf('\t') >= 0) throw untypeable('\t', false);
        if (unicodeKeys == UnicodeKeys.KEY_NAMES || unicodeKeys == UnicodeKeys.TEXT_AS_KEYS) return;
        String checked = settings.unicode() == HumanInputSettings.UnicodePolicy.TRANSLITERATE
                ? HumanTyping.transliterate(text) : text;
        checked.codePoints().filter(c -> !HumanTyping.inLayout(c)).findFirst()
                .ifPresent(c -> { throw untypeable(c, false); });
    }

    private void playKeys(InputDevice device, List<HumanTyping.KeyEvent> events, boolean countText) {
        long start = device.now();
        List<String> inserted = new ArrayList<>(); // a list: the same character can be down twice
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
            // Playwright4J #59 sends inserted text as one real keyDown/keyUp per character.
            if (!event.layout() && unicodeKeys == UnicodeKeys.TEXT_AS_KEYS && !"\t".equals(key)) {
                device.insertText(key);
                inserted.add(key);
                typed++;
                continue;
            }
            // A tab key would leave the field; a tab character can only be inserted.
            if (!event.layout() && (unicodeKeys == UnicodeKeys.NONE || "\t".equals(key))) {
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
                if (!event.layout()) unicodeKeys = UnicodeKeys.KEY_NAMES;
            } catch (PlaywrightException refused) {
                if (event.layout() || !String.valueOf(refused.getMessage()).contains("Unknown key")) throw refused;
                unicodeKeys = UnicodeKeys.NONE;
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
            else if ("Enter".equals(key) || key.codePointCount(0, key.length()) == 1) typed++;
        }
    }

    /** Releases held keys and erases what was typed, so a refused character leaves no half-typed text behind. */
    private IllegalStateException takeBack(InputDevice device, Set<String> pressed, int typed, String key) {
        for (String held : new ArrayList<>(pressed)) device.keyUp(held);
        playKeys(device, typing.erase(typed), false);
        return untypeable(key.codePointAt(0), typed > 0);
    }

    /** Windows (CRLF) and old Mac (CR) line ends as {@code \n}: one line break is one Enter. */
    static String lineEnds(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** A line break before the end; one trailing newline (also CRLF) presses Enter on purpose. */
    static boolean lineBreakInside(String text) {
        String normalized = lineEnds(text);
        return normalized.indexOf('\n') >= 0 && normalized.indexOf('\n') < normalized.length() - 1;
    }

    private static IllegalStateException untypeable(int codePoint, boolean erased) {
        if (codePoint == '\t') {
            return new IllegalStateException("Cannot type a tab character as a real key: the Tab key would move the"
                    + " focus to the next control. Remove it, or set ARGOS_INPUT_UNICODE=insert (visible to the page).");
        }
        return new IllegalStateException("Cannot type '" + new String(Character.toChars(codePoint))
                + "' as a real key: Playwright Core sends characters outside the US layout only through a page"
                + " script (playwright4j#36); Playwright4J with playwright4j#59 types them as real keys."
                + (erased ? " The text typed so far was erased." : " Nothing was typed into the field.")
                + " Remove the character, or opt in to ARGOS_INPUT_UNICODE="
                + "transliterate (changes the text: ä -> ae) or insert (debug only, visible to the page).");
    }
}
