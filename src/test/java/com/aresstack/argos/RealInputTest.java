package com.aresstack.argos;

import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealInputTest {
    /** Records what would go to BiDi; time only advances by sleeping. */
    static final class RecordingDevice implements InputDevice {
        final List<String> calls = new ArrayList<>();
        final boolean unicodeKeys;
        long clock;

        RecordingDevice(boolean unicodeKeys) {
            this.unicodeKeys = unicodeKeys;
        }

        @Override public void move(double x, double y) { calls.add("move " + x + "," + y); }
        @Override public void down(MouseButton button) {
            calls.add(button == MouseButton.LEFT ? "down" : "down " + button);
        }
        @Override public void up(MouseButton button) { calls.add(button == MouseButton.LEFT ? "up" : "up " + button); }
        @Override public void wheel(double deltaX, double deltaY) {
            calls.add(deltaX == 0 ? "wheel " + deltaY : "wheel " + deltaX + "," + deltaY);
        }

        @Override public void keyDown(String key) {
            if (!unicodeKeys && key.codePointCount(0, key.length()) == 1 && !HumanTyping.inLayout(key.codePointAt(0))) {
                throw new PlaywrightException("Unknown key: \"" + key + "\"");
            }
            calls.add("keyDown " + key);
        }

        @Override public void keyUp(String key) { calls.add("keyUp " + key); }
        @Override public void insertText(String text) { calls.add("insertText " + text); }
        @Override public void sleep(long millis) { if (millis > 0) clock += millis; }
        @Override public long now() { return clock; }

        long count(String call) {
            return calls.stream().filter(call::equals).count();
        }
    }

    private static RealInput input(HumanInputSettings.UnicodePolicy policy) {
        return new RealInput(new HumanInputSettings(HumanInputSettings.Mode.NATURAL, 1, 0, 7L, policy));
    }

    @Test void strictPolicyRefusesBeforeTheFirstKeyUntilRealUnicodeKeysAreProven() {
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        RecordingDevice device = new RecordingDevice(false);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> input.type(device, "Mä"));
        assertTrue(refused.getMessage().contains("Nothing was typed"), refused.getMessage());
        assertTrue(device.calls.isEmpty(), "fail closed before any key: " + device.calls);
        input.unicodeKeys(RealInput.UnicodeKeys.NONE);
        assertThrows(IllegalStateException.class, () -> input.type(device, "Grüße"));
        assertTrue(device.calls.isEmpty());
    }

    private static List<String> keys(RecordingDevice device) {
        return device.calls.stream().filter(call -> call.startsWith("key")).toList();
    }

    @Test void shortcutHoldsModifiersInOrderAndReleasesThemInReverse() {
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        RecordingDevice device = new RecordingDevice(false);
        input.press(device, input.chord("Control+Shift+ArrowLeft"), 2);
        assertEquals(List.of("keyDown Control", "keyDown Shift", "keyDown ArrowLeft", "keyUp ArrowLeft",
                "keyDown ArrowLeft", "keyUp ArrowLeft", "keyUp Shift", "keyUp Control"), keys(device));
        assertTrue(device.clock > 0, "human pauses between the events");
    }

    @Test void singleNamedKeysAndAliasesAreRecognised() {
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        assertEquals("Escape", input.chord("esc").toString());
        assertEquals("Control+a", input.chord("Strg+a").toString());
        assertEquals("Meta+Enter", input.chord("cmd+Return").toString());
        assertEquals("Shift+Tab", input.chord(" shift+tab ").toString());
        assertEquals(" ", input.chord("Space").key());
        assertEquals("Control++", input.chord("Control++").toString());
        assertEquals("+", input.chord("+").key());
        RecordingDevice device = new RecordingDevice(false);
        input.press(device, input.chord("Enter"), 1);
        assertEquals(List.of("keyDown Enter", "keyUp Enter"), keys(device));
    }

    @Test void badCombinationsAreRefusedBeforeAnyKey() {
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        for (String bad : List.of("", "Control", "Control+", "Hyper+a", "Enterr", "ab", "Shift+Shift+a", "\uE007",
                "Control+w", "ctrl+W", "Alt+F4", "ControlOrMeta+t")) {
            assertThrows(IllegalArgumentException.class, () -> input.chord(bad), bad);
        }
    }

    @Test void unicodeKeysArePressedOnlyWhenPlaywrightSendsThemAsRealKeys() {
        RealInput input = input(HumanInputSettings.UnicodePolicy.INSERT);
        assertThrows(IllegalStateException.class, () -> input.chord("ä"), "insert is text, not a key");
        input.unicodeKeys(RealInput.UnicodeKeys.TEXT_AS_KEYS);
        assertThrows(IllegalStateException.class, () -> input.chord("Control+ä"));
        input.unicodeKeys(RealInput.UnicodeKeys.KEY_NAMES);
        RecordingDevice device = new RecordingDevice(true);
        input.press(device, input.chord("Alt+ä"), 1);
        assertEquals(List.of("keyDown Alt", "keyDown ä", "keyUp ä", "keyUp Alt"), keys(device));
    }

    @Test void heldModifiersAreReleasedWhenAKeyFails() {
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        input.unicodeKeys(RealInput.UnicodeKeys.KEY_NAMES);
        RecordingDevice device = new RecordingDevice(false); // refuses ä although the probe said yes
        assertThrows(PlaywrightException.class, () -> input.press(device, input.chord("Control+Shift+ä"), 1));
        assertEquals(List.of("keyDown Control", "keyDown Shift", "keyUp Shift", "keyUp Control"), keys(device));
    }

    @Test void unexpectedRefusalAfterAPositiveProbeErasesTheTypedText() {
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        input.unicodeKeys(RealInput.UnicodeKeys.KEY_NAMES);
        RecordingDevice device = new RecordingDevice(false);
        assertThrows(IllegalStateException.class, () -> input.type(device, "Mä"));
        assertEquals(1, device.count("keyDown Backspace"), device.calls.toString());
        assertTrue(device.calls.stream().noneMatch(call -> call.startsWith("insertText")));
        assertEquals(device.count("keyDown Shift"), device.count("keyUp Shift"));
    }

    @Test void strictRollbackAlsoErasesLineBreaks() {
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        input.unicodeKeys(RealInput.UnicodeKeys.KEY_NAMES);
        RecordingDevice device = new RecordingDevice(false);
        assertThrows(IllegalStateException.class, () -> input.type(device, "a\nbä"));
        assertEquals(3, device.count("keyDown Backspace"), device.calls.toString());
    }

    @Test void tabsAreRejectedBeforeTheFirstKeyUnlessInsertIsAllowed() {
        RecordingDevice device = new RecordingDevice(true);
        assertThrows(IllegalStateException.class,
                () -> input(HumanInputSettings.UnicodePolicy.STRICT).type(device, "a\tb"));
        assertTrue(device.calls.isEmpty(), device.calls.toString());
        RecordingDevice inserting = new RecordingDevice(true);
        input(HumanInputSettings.UnicodePolicy.INSERT).type(inserting, "a\tb");
        assertEquals(1, inserting.count("insertText \t"));
        assertEquals(0, inserting.count("keyDown \t"));
    }

    @Test void insertPolicyFallsBackToPlaywrightsInsertText() {
        RecordingDevice device = new RecordingDevice(false);
        input(HumanInputSettings.UnicodePolicy.INSERT).type(device, "aä");
        assertEquals(1, device.count("insertText ä"));
        assertEquals(0, device.count("keyUp ä"));
        assertEquals(1, device.count("keyDown a"));
    }

    @Test void transliterationTypesUmlautsAsRealKeys() {
        RecordingDevice device = new RecordingDevice(false);
        input(HumanInputSettings.UnicodePolicy.TRANSLITERATE).type(device, "Müller");
        assertEquals("Mueller", typed(device.calls));
        assertTrue(device.calls.stream().noneMatch(call -> call.startsWith("insertText")));
    }

    @Test void unicodeGoesAsRealKeyWhenPlaywrightAcceptsIt() {
        RecordingDevice device = new RecordingDevice(true);
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        input.unicodeKeys(RealInput.UnicodeKeys.KEY_NAMES);
        input.type(device, "ä€");
        assertEquals(1, device.count("keyDown ä"));
        assertEquals(1, device.count("keyDown €"));
        assertFalse(device.calls.stream().anyMatch(call -> call.startsWith("insertText")));
    }

    @Test void playwright4jTextKeysTypeUnicodeThroughInsertTextOnePerCharacter() {
        // Playwright4J #59: key names stay refused, insertText becomes a real keyDown/keyUp per character.
        RecordingDevice device = new RecordingDevice(false);
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        input.unicodeKeys(RealInput.UnicodeKeys.TEXT_AS_KEYS);
        input.type(device, "Grüße ßß");
        assertEquals(1, device.count("insertText ü"));
        assertEquals(3, device.count("insertText ß"));
        assertEquals(0, device.count("keyDown ü") + device.count("keyUp ü") + device.count("keyUp ß"));
        assertEquals(1, device.count("keyDown G"));
        assertTrue(device.calls.stream().filter(call -> call.startsWith("insertText"))
                .allMatch(call -> call.codePointCount(0, call.length()) == "insertText ".length() + 1), device.calls.toString());
    }

    @Test void tabsStayRefusedWithPlaywright4jTextKeys() {
        RecordingDevice device = new RecordingDevice(false);
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        input.unicodeKeys(RealInput.UnicodeKeys.TEXT_AS_KEYS);
        assertThrows(IllegalStateException.class, () -> input.type(device, "ä\tb"));
        assertTrue(device.calls.isEmpty(), device.calls.toString());
    }

    @Test void webDriverReservedCodePointsAreRefusedBeforeTheFirstKey() {
        for (HumanInputSettings.UnicodePolicy policy : HumanInputSettings.UnicodePolicy.values()) {
            RecordingDevice device = new RecordingDevice(true);
            RealInput input = input(policy);
            input.unicodeKeys(RealInput.UnicodeKeys.KEY_NAMES);
            assertThrows(IllegalArgumentException.class, () -> input.type(device, "ab\uE007"));
            assertTrue(device.calls.isEmpty(), policy + ": " + device.calls);
        }
    }

    @Test void onlyATrailingNewlineCountsAsEnterForSingleLineFields() {
        assertFalse(RealInput.lineBreakInside("x"));
        assertFalse(RealInput.lineBreakInside("x\n"));
        assertFalse(RealInput.lineBreakInside("x\r\n"));
        assertFalse(RealInput.lineBreakInside("x\r"));
        assertTrue(RealInput.lineBreakInside("x\ny"));
        assertTrue(RealInput.lineBreakInside("x\n\n"));
        assertTrue(RealInput.lineBreakInside("x\ry"));
    }

    @Test void aTrailingCrlfIsOneEnter() {
        RecordingDevice device = new RecordingDevice(false);
        input(HumanInputSettings.UnicodePolicy.STRICT).type(device, "x\r\n");
        assertEquals(1, device.count("keyDown Enter"), device.calls.toString());
        RecordingDevice lines = new RecordingDevice(false);
        input(HumanInputSettings.UnicodePolicy.STRICT).type(lines, "a\r\nb\rc");
        assertEquals(2, lines.count("keyDown Enter"), lines.calls.toString());
    }

    @Test void capitalUmlautsAreTypedWithShiftHeld() {
        RecordingDevice device = new RecordingDevice(false);
        RealInput input = input(HumanInputSettings.UnicodePolicy.STRICT);
        input.unicodeKeys(RealInput.UnicodeKeys.TEXT_AS_KEYS);
        input.type(device, "Äa");
        List<String> keys = device.calls;
        int shiftDown = keys.indexOf("keyDown Shift");
        assertTrue(shiftDown >= 0 && shiftDown < keys.indexOf("insertText Ä"), keys.toString());
        assertTrue(keys.indexOf("keyUp Shift") > keys.indexOf("insertText Ä"), keys.toString());
        assertTrue(keys.indexOf("keyUp Shift") < keys.indexOf("keyDown a"), keys.toString());
    }

    @Test void capitalsAreTypedWithShiftHeld() {
        RecordingDevice device = new RecordingDevice(false);
        input(HumanInputSettings.UnicodePolicy.STRICT).type(device, "aB");
        List<String> keys = device.calls;
        assertTrue(keys.indexOf("keyDown Shift") < keys.indexOf("keyDown B"));
        assertTrue(keys.indexOf("keyUp B") < keys.indexOf("keyUp Shift"));
    }

    @Test void viewportPointsStayInsideAfterConversionToWholePixels() {
        HumanMotion.Box view = new HumanMotion.Box(0, 0, 1280, 720);
        assertEquals(new HumanMotion.Point(1279, 719), RealInput.viewportPoint(1279.6, 719.999, view));
        assertEquals(new HumanMotion.Point(0, 0), RealInput.viewportPoint(0, 0.4, view));
        assertEquals(new HumanMotion.Point(640, 360), RealInput.viewportPoint(640, 360, view));
        for (double[] outside : new double[][] {{1280, 10}, {10, 720}, {-0.1, 10}, {10, -1}, {Double.NaN, 1},
                {1, Double.POSITIVE_INFINITY}}) {
            assertThrows(IllegalArgumentException.class, () -> RealInput.viewportPoint(outside[0], outside[1], view),
                    java.util.Arrays.toString(outside));
        }
        HumanMotion.Box fractional = new HumanMotion.Box(0, 0, 1279.5, 719.5);
        assertEquals(new HumanMotion.Point(1279, 719), RealInput.viewportPoint(1279.4, 719.4, fractional));
    }

    /** The text a field would contain after these key downs. */
    static String typed(List<String> calls) {
        StringBuilder text = new StringBuilder();
        for (String call : calls) {
            if (!call.startsWith("keyDown ")) continue;
            String key = call.substring("keyDown ".length());
            if (key.equals("Backspace")) text.setLength(Math.max(0, text.length() - 1));
            else if (key.codePointCount(0, key.length()) == 1) text.append(key);
        }
        return text.toString();
    }
}
