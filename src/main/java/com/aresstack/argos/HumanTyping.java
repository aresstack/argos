package com.aresstack.argos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Plans typing as a timeline of key presses and releases; pure arithmetic, no browser access. Intervals between
 * keys vary log-normally, are longer after spaces and punctuation, now and then a person hesitates; capitals and
 * shifted symbols are typed with Shift held, so the page sees {@code shiftKey} as on a real keyboard (Playwright's
 * own {@code type} omits Shift). Fast neighbouring keys may roll over (next key down before the last one is up).
 * With a typo rate, a neighbouring key is hit first and corrected with Backspace.
 */
final class HumanTyping {
    enum Kind { DOWN, UP }

    /**
     * One key event, {@code at} milliseconds after typing started. {@code layout} is false for characters outside
     * the US keyboard layout, which Playwright may refuse to send as a real key.
     */
    record KeyEvent(Kind kind, String key, long at, boolean layout) { }

    private static final String SHIFTED_SYMBOLS = "~!@#$%^&*()_+{}|:\"<>?";
    private static final String[] ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final Map<Character, String> ASCII = Map.ofEntries(
            Map.entry('ä', "ae"), Map.entry('ö', "oe"), Map.entry('ü', "ue"),
            Map.entry('Ä', "Ae"), Map.entry('Ö', "Oe"), Map.entry('Ü', "Ue"), Map.entry('ß', "ss"),
            Map.entry('„', "\""), Map.entry('“', "\""), Map.entry('”', "\""), Map.entry('»', "\""),
            Map.entry('«', "\""), Map.entry('‚', "'"), Map.entry('‘', "'"), Map.entry('’', "'"),
            Map.entry('–', "-"), Map.entry('—', "-"), Map.entry('…', "..."), Map.entry(' ', " "));
    /** A tool call must stay well inside the browser task timeout. */
    static final long DEFAULT_BUDGET_MILLIS = 25_000;

    private final RandomGenerator random;
    private final HumanInputSettings settings;
    private final double tempo;

    HumanTyping(RandomGenerator random, HumanInputSettings settings) {
        this.random = random;
        this.settings = settings;
        this.tempo = settings.speed() / (settings.mode() == HumanInputSettings.Mode.CAREFUL ? 1.3 : 1.0);
    }

    private boolean off() {
        return settings.mode() == HumanInputSettings.Mode.OFF;
    }

    /** The timeline for {@code text}; a trailing newline presses Enter after a short look at the input. */
    List<KeyEvent> type(String text) {
        return type(text, DEFAULT_BUDGET_MILLIS);
    }

    List<KeyEvent> type(String text, long budgetMillis) {
        if (settings.unicode() == HumanInputSettings.UnicodePolicy.TRANSLITERATE) text = transliterate(text);
        Timeline line = new Timeline();
        int[] codePoints = text.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            int codePoint = codePoints[i];
            boolean last = i == codePoints.length - 1;
            if (last && codePoint == '\n' && i > 0) line.time += off() ? 0 : scaled(150 + random.nextInt(450));
            if (i > 0 && !off() && settings.typoRate() > 0 && isLetter(codePoint)
                    && random.nextDouble() < settings.typoRate()) {
                line.press(neighbour(codePoint), Character.isUpperCase(codePoint), true);
                // Noticing the mistake takes longer than a normal interval.
                line.time += scaled(220 + random.nextInt(400));
                line.press("Backspace", false, true);
                line.time += gap(' ', codePoint);
            }
            line.press(keyName(codePoint), shifted(codePoint), inLayout(codePoint));
            if (!last) line.time += gap(codePoint, codePoints[i + 1]);
        }
        line.releaseShift();
        return fit(line.events, budgetMillis);
    }

    /** Selects the field's content and deletes it: Ctrl (Cmd on macOS) + A, then Backspace. */
    List<KeyEvent> clearField() {
        Timeline line = new Timeline();
        long hold = hold();
        line.add(Kind.DOWN, "ControlOrMeta", 0);
        line.add(Kind.DOWN, "a", off() ? 10 : scaled(60 + random.nextInt(100)));
        line.add(Kind.UP, "a", line.lastAt() + hold);
        line.add(Kind.UP, "ControlOrMeta", line.lastAt() + (off() ? 10 : scaled(30 + random.nextInt(80))));
        line.time = line.lastAt() + (off() ? 20 : scaled(120 + random.nextInt(220)));
        line.press("Backspace", false, true);
        return line.events;
    }

    /** One Backspace per character, quickly, to take back text typed so far. */
    List<KeyEvent> erase(int characters) {
        Timeline line = new Timeline();
        for (int i = 0; i < characters; i++) {
            line.press("Backspace", false, true);
            line.time += off() ? 25 : scaled(logNormal(70, 0.25, 35, 160));
        }
        return line.events;
    }

    private final class Timeline {
        final List<KeyEvent> events = new ArrayList<>();
        /** When the next key goes down. */
        long time;
        long lastUp;
        /** When each key was last released: the same key cannot go down again while it is still held. */
        final Map<String, Long> releases = new HashMap<>();
        boolean shiftHeld;

        void add(Kind kind, String key, long at) {
            add(kind, key, at, true);
        }

        void add(Kind kind, String key, long at, boolean layout) {
            events.add(new KeyEvent(kind, key, at, layout));
        }

        long lastAt() {
            return events.get(events.size() - 1).at();
        }

        void press(String key, boolean shift, boolean layout) {
            boolean shiftChange = shift != shiftHeld;
            if (shift && !shiftHeld) {
                // Shift goes down a little before the letter, after the previous key is released.
                long at = Math.max(lastUp + 10, time - (off() ? 15 : scaled(40 + random.nextInt(70))));
                add(Kind.DOWN, "Shift", at);
                time = Math.max(time, at + (off() ? 15 : 30));
                shiftHeld = true;
            } else if (!shift && shiftHeld) {
                releaseShift();
                time = Math.max(time, lastUp + 20);
            }
            // Roll-over only between different keys of the same Shift state.
            boolean rollover = !off() && !shiftChange && layout;
            if (!rollover) time = Math.max(time, lastUp + 5);
            time = Math.max(time, releases.getOrDefault(key, -5L) + 5);
            long hold = hold();
            add(Kind.DOWN, key, time, layout);
            add(Kind.UP, key, time + hold, layout);
            lastUp = Math.max(lastUp, time + hold);
            releases.put(key, time + hold);
        }

        void releaseShift() {
            if (!shiftHeld) return;
            long at = lastUp + (off() ? 10 : scaled(15 + random.nextInt(55)));
            add(Kind.UP, "Shift", at);
            lastUp = at;
            shiftHeld = false;
        }
    }

    /** The interval before the next key goes down. */
    private long gap(int previous, int next) {
        if (off()) return 30;
        double millis = logNormal(150, 0.35, 50, 650);
        if (previous == ' ' || isPunctuation(previous)) millis *= 1.25;
        if (previous == next) millis *= 0.85;
        // Thinking about the next word, or a short hesitation inside one.
        if (previous == ' ' && random.nextDouble() < 0.07) millis += 250 + random.nextInt(850);
        else if (random.nextDouble() < 0.02) millis += 150 + random.nextInt(250);
        return scaled(millis);
    }

    private long hold() {
        return off() ? 20 : scaled(logNormal(85, 0.25, 35, 180));
    }

    /** Long texts are compressed in time, in proportion, so the order of events stays the same. */
    private static List<KeyEvent> fit(List<KeyEvent> events, long budgetMillis) {
        events.sort(Comparator.comparingLong(KeyEvent::at));
        long total = events.isEmpty() ? 0 : events.get(events.size() - 1).at();
        if (total <= budgetMillis) return events;
        double factor = (double) budgetMillis / total;
        List<KeyEvent> fitted = new ArrayList<>(events.size());
        for (KeyEvent event : events) {
            fitted.add(new KeyEvent(event.kind(), event.key(), Math.round(event.at() * factor), event.layout()));
        }
        return fitted;
    }

    private long scaled(double millis) {
        return Math.max(1, Math.round(millis / tempo));
    }

    private double logNormal(double median, double sigma, double min, double max) {
        return Math.min(max, Math.max(min, median * Math.exp(random.nextGaussian() * sigma)));
    }

    private String neighbour(int codePoint) {
        char lower = Character.toLowerCase((char) codePoint);
        for (String row : ROWS) {
            int index = row.indexOf(lower);
            if (index < 0) continue;
            int pick = index == 0 ? 1 : index == row.length() - 1 ? index - 1
                    : index + (random.nextBoolean() ? 1 : -1);
            char wrong = row.charAt(pick);
            return String.valueOf(Character.isUpperCase(codePoint) ? Character.toUpperCase(wrong) : wrong);
        }
        return String.valueOf((char) codePoint);
    }

    static String transliterate(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.chars().forEach(c -> out.append(ASCII.getOrDefault((char) c, String.valueOf((char) c))));
        return out.toString();
    }

    /** Playwright's key name for a character. */
    static String keyName(int codePoint) {
        return switch (codePoint) {
            case '\n', '\r' -> "Enter";
            default -> new String(Character.toChars(codePoint));
        };
    }

    /**
     * Characters of the US layout, which Playwright sends as real keys. A tab is not among them: as a key it would
     * move the focus to the next control instead of entering a tab character.
     */
    static boolean inLayout(int codePoint) {
        return codePoint >= 0x20 && codePoint <= 0x7e || codePoint == '\n' || codePoint == '\r';
    }

    static boolean shifted(int codePoint) {
        return codePoint >= 'A' && codePoint <= 'Z' || SHIFTED_SYMBOLS.indexOf(codePoint) >= 0;
    }

    private static boolean isLetter(int codePoint) {
        return codePoint < 0x80 && Character.isLetter(codePoint);
    }

    private static boolean isPunctuation(int codePoint) {
        return ".,;:!?".indexOf(codePoint) >= 0;
    }
}
