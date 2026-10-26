package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanTypingTest {
    private static HumanTyping typing(long seed, double typoRate, HumanInputSettings.UnicodePolicy policy) {
        HumanInputSettings settings = new HumanInputSettings(HumanInputSettings.Mode.NATURAL, 1, typoRate, seed, policy);
        return new HumanTyping(new SplittableRandom(seed), settings);
    }

    private static HumanTyping typing(long seed) {
        return typing(seed, 0, HumanInputSettings.UnicodePolicy.STRICT);
    }

    @Test void typesEveryCharacterInOrderAndReleasesEveryKey() {
        String text = "Hello World, (Argos) 2.0!";
        for (long seed = 1; seed <= 20; seed++) {
            List<HumanTyping.KeyEvent> events = typing(seed).type(text);
            assertEquals(text, result(events));
            Set<String> held = new HashSet<>();
            long previous = -1;
            for (HumanTyping.KeyEvent event : events) {
                assertTrue(event.at() >= previous, "sorted by time");
                previous = event.at();
                if (event.kind() == HumanTyping.Kind.DOWN) assertTrue(held.add(event.key()), "no double press " + event);
                else assertTrue(held.remove(event.key()), "release of a held key " + event);
            }
            assertTrue(held.isEmpty(), "everything released: " + held);
        }
    }

    @Test void aKeyIsNeverPressedAgainWhileItIsStillHeld() {
        for (long seed = 1; seed <= 5000; seed++) {
            Set<String> held = new HashSet<>();
            for (HumanTyping.KeyEvent event : typing(seed).type("abab aba")) {
                if (event.kind() == HumanTyping.Kind.DOWN) assertTrue(held.add(event.key()), "seed " + seed + " " + event);
                else held.remove(event.key());
            }
        }
    }

    @Test void shiftIsHeldExactlyAroundShiftedCharacters() {
        for (long seed = 1; seed <= 20; seed++) {
            boolean shift = false;
            for (HumanTyping.KeyEvent event : typing(seed).type("aBC d!e Äö")) {
                if (event.key().equals("Shift")) {
                    shift = event.kind() == HumanTyping.Kind.DOWN;
                } else if (event.kind() == HumanTyping.Kind.DOWN) {
                    assertEquals(HumanTyping.shifted(event.key().codePointAt(0)), shift, "Shift state at " + event);
                }
            }
            assertFalse(shift);
        }
    }

    @Test void intervalsVaryLikeAPersonsAndAreNotFixed() {
        List<HumanTyping.KeyEvent> events = typing(5).type("the quick brown fox jumps over the lazy dog");
        List<Long> downs = new ArrayList<>();
        events.stream().filter(e -> e.kind() == HumanTyping.Kind.DOWN).forEach(e -> downs.add(e.at()));
        double[] gaps = new double[downs.size() - 1];
        for (int i = 1; i < downs.size(); i++) gaps[i - 1] = downs.get(i) - downs.get(i - 1);
        double mean = java.util.Arrays.stream(gaps).average().orElse(0);
        double deviation = Math.sqrt(java.util.Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElse(0));
        assertTrue(mean > 90 && mean < 400, "mean " + mean);
        assertTrue(deviation > 25, "deviation " + deviation);
    }

    @Test void longTextsAreCompressedIntoTheBudget() {
        List<HumanTyping.KeyEvent> events = typing(9).type("x".repeat(400), 10_000);
        assertTrue(events.get(events.size() - 1).at() <= 10_000);
        assertEquals("x".repeat(400), result(events));
    }

    @Test void typosAreCorrectedWithBackspace() {
        String text = "research gate publications";
        List<HumanTyping.KeyEvent> events = typing(11, 0.2, HumanInputSettings.UnicodePolicy.STRICT).type(text);
        assertTrue(events.stream().anyMatch(e -> e.key().equals("Backspace")), "at least one typo");
        assertEquals(text, result(events));
    }

    @Test void trailingNewlinePressesEnterAndUmlautsAreMarkedOutsideTheLayout() {
        List<HumanTyping.KeyEvent> events = typing(2).type("Grüße\n");
        assertEquals("Enter", events.stream().filter(e -> e.kind() == HumanTyping.Kind.DOWN)
                .reduce((a, b) -> b).orElseThrow().key());
        assertTrue(events.stream().filter(e -> e.key().equals("ü")).noneMatch(HumanTyping.KeyEvent::layout));
        assertEquals("Gruesse", result(typing(2, 0, HumanInputSettings.UnicodePolicy.TRANSLITERATE).type("Grüße")));
    }

    @Test void sameSeedTypesIdentically() {
        assertEquals(typing(77).type("Deterministic"), typing(77).type("Deterministic"));
    }

    @Test void clearingSelectsAllAndDeletes() {
        List<String> downs = typing(1).clearField().stream().filter(e -> e.kind() == HumanTyping.Kind.DOWN)
                .map(HumanTyping.KeyEvent::key).toList();
        assertEquals(List.of("ControlOrMeta", "a", "Backspace"), downs);
    }

    /** The text these key downs produce in an empty field. */
    private static String result(List<HumanTyping.KeyEvent> events) {
        StringBuilder text = new StringBuilder();
        for (HumanTyping.KeyEvent event : events) {
            if (event.kind() != HumanTyping.Kind.DOWN) continue;
            String key = event.key();
            if (key.equals("Backspace")) text.setLength(Math.max(0, text.length() - 1));
            else if (key.equals("Enter")) text.append('\n');
            else if (key.codePointCount(0, key.length()) == 1) text.append(key);
        }
        return text.toString();
    }
}
