package com.aresstack.argos;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * How human the real BiDi input behaves. Every variant still reaches the page only as trusted
 * {@code input.performActions} events; the settings change paths and timing, never the channel.
 *
 * @param mode     {@code off}: straight moves and short fixed pauses; {@code natural} (default); {@code careful}:
 *                 slower, with more hesitation
 * @param speed    multiplies the tempo of mouse and keyboard, 0.25 to 4 (default 1)
 * @param typoRate chance per letter of hitting a neighbouring key first and correcting it with Backspace, 0 to 0.2
 *                 (default 0)
 * @param seed     fixed random seed for reproducible input, or {@code null} for a fresh one per browser
 * @param unicode  what happens to characters outside the US keyboard layout (umlauts, €, emoji)
 */
record HumanInputSettings(Mode mode, double speed, double typoRate, Long seed, UnicodePolicy unicode) {

    enum Mode { OFF, NATURAL, CAREFUL }

    /**
     * Playwright Core 1.62 sends characters outside the US layout only through {@code window.__pw_bidiInsertText}, a
     * main-world function that dispatches an untrusted {@code InputEvent} (playwright4j#36). Argos tries a genuine
     * key event first; this policy decides what happens when Playwright refuses it.
     */
    enum UnicodePolicy {
        /** Fail closed: the text is refused before the field is focused or changed. Nothing page-visible. */
        STRICT,
        /**
         * Deliberate opt-in only, because it changes the text (Grüße becomes Gruesse, wrong for names in forms):
         * German umlauts, ß and typographic punctuation become ASCII (ä → ae, „ → "), the rest as STRICT.
         */
        TRANSLITERATE,
        /**
         * Debug/compatibility mode, never stealth input: falls back to Playwright's insertText, so the text arrives
         * as an untrusted, script-made event.
         */
        INSERT
    }

    HumanInputSettings {
        if (mode == null) mode = Mode.NATURAL;
        if (unicode == null) unicode = UnicodePolicy.STRICT;
        if (!(speed >= 0.25 && speed <= 4)) throw new IllegalArgumentException("HUMAN_SPEED must be 0.25 to 4");
        if (!(typoRate >= 0 && typoRate <= 0.2)) {
            throw new IllegalArgumentException("HUMAN_TYPO_RATE must be 0 to 0.2");
        }
    }

    static HumanInputSettings defaults() {
        return new HumanInputSettings(Mode.NATURAL, 1, 0, null, UnicodePolicy.STRICT);
    }

    /** A random source for one browser: seeded when {@link #seed} is set. */
    RandomGenerator random() {
        return seed == null ? new SplittableRandom() : new SplittableRandom(seed);
    }

    /** Reads {@code HUMAN_*} environment variables first, then {@code input.*} keys of argos.properties. */
    static HumanInputSettings fromEnvironment() {
        Properties properties = new Properties();
        Path file = Path.of(System.getProperty("argos.config",
                Path.of(System.getProperty("user.home"), ".argos", "argos.properties").toString()));
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException unreadable) {
                throw new IllegalStateException("Cannot read " + file, unreadable);
            }
        }
        return from(name -> {
            String env = EnvironmentVariables.system().get(name);
            if (env != null && !env.isBlank()) return env;
            return properties.getProperty(PROPERTY_KEYS.apply(name));
        });
    }

    private static final Function<String, String> PROPERTY_KEYS = name -> switch (name) {
        case "HUMAN_INPUT" -> "input.human";
        case "HUMAN_SPEED" -> "input.speed";
        case "HUMAN_TYPO_RATE" -> "input.typoRate";
        case "HUMAN_SEED" -> "input.seed";
        case "INPUT_UNICODE" -> "input.unicode";
        default -> name;
    };

    static HumanInputSettings from(Function<String, String> lookup) {
        String mode = text(lookup, "HUMAN_INPUT", "natural");
        String unicode = text(lookup, "INPUT_UNICODE", "strict");
        String seed = text(lookup, "HUMAN_SEED", null);
        try {
            return new HumanInputSettings(
                    Mode.valueOf(mode.toUpperCase(Locale.ROOT)),
                    Double.parseDouble(text(lookup, "HUMAN_SPEED", "1")),
                    Double.parseDouble(text(lookup, "HUMAN_TYPO_RATE", "0")),
                    seed == null ? null : Long.parseLong(seed),
                    UnicodePolicy.valueOf(unicode.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid human input setting (HUMAN_INPUT=off|natural|careful, "
                    + "INPUT_UNICODE=strict|transliterate|insert, numbers for speed, typo rate and seed): "
                    + invalid.getMessage(), invalid);
        }
    }

    private static String text(Function<String, String> lookup, String name, String fallback) {
        String value = lookup.apply(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
