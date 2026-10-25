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
        @Override public void down() { calls.add("down"); }
        @Override public void up() { calls.add("up"); }
        @Override public void wheel(double deltaX, double deltaY) { calls.add("wheel " + deltaY); }

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
        assertTrue(RealInput.lineBreakInside("x\ny"));
        assertTrue(RealInput.lineBreakInside("x\n\n"));
        assertTrue(RealInput.lineBreakInside("x\ry"));
    }

    @Test void capitalsAreTypedWithShiftHeld() {
        RecordingDevice device = new RecordingDevice(false);
        input(HumanInputSettings.UnicodePolicy.STRICT).type(device, "aB");
        List<String> keys = device.calls;
        assertTrue(keys.indexOf("keyDown Shift") < keys.indexOf("keyDown B"));
        assertTrue(keys.indexOf("keyUp B") < keys.indexOf("keyUp Shift"));
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
