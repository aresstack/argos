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
    /** "multi" for text areas and editable content, "single" for one-line inputs, "" when not editable. */
    private static final String EDITABLE = """
            element => element.isContentEditable || (element.tagName === 'TEXTAREA' && !element.disabled
                  && !element.readOnly) ? 'multi'
              : element.tagName === 'INPUT' && !element.disabled && !element.readOnly
                  && !/^(button|checkbox|radio|submit|reset|file|image|range|color|hidden)$/i.test(element.type || '')
                ? 'single' : ''""";
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
            Object kind = element.evaluate(EDITABLE);
            if (!"single".equals(kind) && !"multi".equals(kind)) {
                throw new IllegalStateException("Element is not an editable field");
            }
            if ("single".equals(kind) && lineBreakInside(text)) {
                throw new IllegalArgumentException("A single-line field takes no line break inside the text: the"
                        + " Enter key would submit the form halfway. Only a trailing newline presses Enter.");
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

    /** A line break before the end; one trailing newline (also CRLF) presses Enter on purpose. */
    static boolean lineBreakInside(String text) {
        String body = text.endsWith("\r\n") ? text.substring(0, text.length() - 2)
                : text.endsWith("\n") || text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
        return body.indexOf('\n') >= 0 || body.indexOf('\r') >= 0;
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
