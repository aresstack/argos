package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanInputSettingsTest {
    @Test void defaultsAreNaturalAndStrict() {
        HumanInputSettings settings = HumanInputSettings.from(name -> null);
        assertEquals(HumanInputSettings.Mode.NATURAL, settings.mode());
        assertEquals(HumanInputSettings.UnicodePolicy.STRICT, settings.unicode());
        assertEquals(1, settings.speed());
        assertEquals(0, settings.typoRate());
        assertNull(settings.seed());
    }

    @Test void readsAllSettings() {
        Map<String, String> values = Map.of("ARGOS_HUMAN_INPUT", "careful", "ARGOS_HUMAN_SPEED", "1.5",
                "ARGOS_HUMAN_TYPO_RATE", "0.02", "ARGOS_HUMAN_SEED", "1234", "ARGOS_INPUT_UNICODE", "Transliterate");
        HumanInputSettings settings = HumanInputSettings.from(values::get);
        assertEquals(HumanInputSettings.Mode.CAREFUL, settings.mode());
        assertEquals(1.5, settings.speed());
        assertEquals(0.02, settings.typoRate());
        assertEquals(1234L, settings.seed());
        assertEquals(HumanInputSettings.UnicodePolicy.TRANSLITERATE, settings.unicode());
    }

    @Test void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> HumanInputSettings.from(Map.of("ARGOS_HUMAN_INPUT", "robot")::get));
        assertThrows(IllegalArgumentException.class,
                () -> HumanInputSettings.from(Map.of("ARGOS_HUMAN_SPEED", "10")::get));
        assertThrows(IllegalArgumentException.class,
                () -> HumanInputSettings.from(Map.of("ARGOS_HUMAN_TYPO_RATE", "0.5")::get));
    }
}
