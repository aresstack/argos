package com.aresstack.argos.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The fixed task set. Every answer lives only in {@link FixtureWorld}, a fictional site, so a model must use the
 * tools. Prompts are plain user questions; they never name a tool, because choosing the tool is under test.
 */
public final class TaskCatalog {
    private TaskCatalog() {
    }

    /** The deterministic fixture tasks: the regression set. */
    public static List<EvalTask> fixtures() {
        return List.of(
                new EvalTask("find-site", "find a site",
                        "What is the web address (URL) of the official website of the Kestrel Valley Observatory?",
                        List.of("/kvo/?(?=$|[\\s)\\].,;'\"<>*`])"), List.of("/fans/", "hotel-kestrel"), EvalTask.Setup.NONE),
                new EvalTask("search-pick", "web search, pick relevant hits",
                        "How many clear nights did the Kestrel Valley Observatory record in 2025? "
                                + "I need the official number, not a guess.",
                        List.of("\\b212\\b"), List.of("\\b150\\b"), EvalTask.Setup.NONE),
                new EvalTask("follow-link", "follow a link",
                        "Start on {site}/kvo/ and find out which telescope of the observatory has the largest aperture.",
                        List.of("halvard"), List.of(), EvalTask.Setup.NONE),
                new EvalTask("dynamic-page", "read a dynamic page",
                        "Open {site}/kvo/events . Who gives the next public lecture?",
                        List.of("marrow"), List.of(), EvalTask.Setup.NONE),
                new EvalTask("search-retry", "recover from a failed search",
                        "On which hours is the Kestrel Valley Observatory open on Saturdays? Use a web search.",
                        List.of("18[:.]?00|\\b6\\s*(pm|p\\.m\\.)", "23[:.]?30|11[:.]30\\s*(pm|p\\.m\\.)"),
                        List.of("12[:.]?00|\\b12\\s*(pm|p\\.m\\.)|22[:.]?00"), EvalTask.Setup.FAIL_FIRST_SEARCH),
                new EvalTask("broken-link", "recover from a broken page",
                        "What is the name of the protected dark-sky zone around the Kestrel Valley Observatory?",
                        List.of("ravenmoor"), List.of(), EvalTask.Setup.NONE),
                new EvalTask("simple-form", "fill a simple form",
                        "On {site}/kvo/tickets you can check tickets. What is the status and the seat of ticket KV-4471?",
                        List.of("confirmed", "F\\s?12"), List.of(), EvalTask.Setup.NONE),
                new EvalTask("long-page", "read a long page in parts",
                        "Open {site}/kvo/archive . Who was the founding director of the observatory?",
                        List.of("solberg"), List.of(), EvalTask.Setup.NONE));
    }

    /**
     * Optional tasks on the real web (real search engines, real sites). Their answers are stable facts, but the
     * pages are not under our control: use them as a plausibility check, never as a regression gate.
     */
    public static List<EvalTask> live() {
        return List.of(
                new EvalTask("live-rfc-title", "live: search and read",
                        "What is the exact title of RFC 9110? Look it up on the web.",
                        List.of("HTTP Semantics"), List.of(), EvalTask.Setup.LIVE),
                new EvalTask("live-rfc-editor", "live: open and read",
                        "Open https://www.rfc-editor.org/rfc/rfc9110 and tell me the surname of its first listed editor.",
                        List.of("Fielding"), List.of(), EvalTask.Setup.LIVE),
                new EvalTask("live-jep", "live: search, pick and read",
                        "Which JDK Enhancement Proposal (JEP) number made virtual threads a final (non-preview) "
                                + "feature of Java? Check it on the web.",
                        List.of("\\b444\\b"), List.of(), EvalTask.Setup.LIVE));
    }

    public static List<EvalTask> all() {
        List<EvalTask> all = new ArrayList<>(fixtures());
        all.addAll(live());
        return List.copyOf(all);
    }

    /**
     * {@code fixtures} (default), {@code live}, {@code all}, or task ids (comma-separated). Unknown ids fail
     * instead of being skipped.
     */
    public static List<EvalTask> select(String ids) {
        if (ids == null || ids.isBlank() || ids.trim().equalsIgnoreCase("fixtures")) return fixtures();
        if (ids.trim().equalsIgnoreCase("live")) return live();
        if (ids.trim().equalsIgnoreCase("all")) return all();
        List<EvalTask> chosen = new ArrayList<>();
        Set<String> known = new java.util.LinkedHashSet<>();
        all().forEach(task -> known.add(task.id()));
        for (String raw : ids.split(",")) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            EvalTask task = all().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + raw.trim() + " (known: "
                            + String.join(", ", known) + ")"));
            chosen.add(task);
        }
        return List.copyOf(chosen);
    }
}
