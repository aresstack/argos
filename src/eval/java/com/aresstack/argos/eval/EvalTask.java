package com.aresstack.argos.eval;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One task of the fixed set.
 *
 * @param prompt    the user message; {@code {site}} becomes the fixture site's base URL
 * @param expected  patterns that must all occur in the final answer (case-insensitive)
 * @param forbidden patterns of known wrong answers (distractors) that must not occur
 * @param setup     what the fixture world does differently for this task; {@code LIVE} uses the real web and
 *                  Argos' configured search engines instead of the fixture world
 * @param needsForm the task requires submitting a form, which the six compact tools cannot do
 */
public record EvalTask(String id, String category, String prompt, List<String> expected, List<String> forbidden,
                       Setup setup, boolean needsForm) {
    public enum Setup { NONE, FAIL_FIRST_SEARCH, LIVE }

    public EvalTask(String id, String category, String prompt, List<String> expected, List<String> forbidden,
                    Setup setup) {
        this(id, category, prompt, expected, forbidden, setup, false);
    }

    /** Whether the profile's tools can solve the task at all; inapplicable runs are reported apart. */
    public boolean solvableWith(EvalProfile profile) {
        return !needsForm || profile.canSubmitForms();
    }

    public boolean live() {
        return setup == Setup.LIVE;
    }

    public String promptFor(String siteBase) {
        return prompt.replace("{site}", siteBase);
    }

    /** Success means: every expected pattern found, no distractor pattern found. */
    public boolean accepts(String answer) {
        String text = answer == null ? "" : answer;
        return expected.stream().allMatch(pattern -> find(pattern, text))
                && forbidden.stream().noneMatch(pattern -> find(pattern, text));
    }

    private static boolean find(String pattern, String text) {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }
}
