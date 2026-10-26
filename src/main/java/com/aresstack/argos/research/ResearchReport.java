package com.aresstack.argos.research;

import java.util.List;

/**
 * The outcome of one run: why it stopped, what it accepted, and the documents it saw but did not open.
 *
 * @param notes short diagnostics in the web_search style ("SEARCH: …", "BLOCKED: …")
 */
public record ResearchReport(String question, ResearchStopReason stopReason, List<ResearchSource> sources,
                             List<String> documents, ResearchProgress progress, List<String> notes) {
    public ResearchReport {
        sources = List.copyOf(sources);
        documents = List.copyOf(documents);
        notes = List.copyOf(notes);
    }

    /** Compact numbered text for small models: verdict first, then sources, then what to do next. */
    public String format() {
        StringBuilder text = new StringBuilder();
        text.append("Research: ").append(question).append('\n');
        text.append("Stopped: ").append(stopReason).append(" (").append(progress.sources()).append(" sources from ")
                .append(progress.distinctHosts()).append(" sites, ").append(progress.pagesRead()).append(" pages read, ")
                .append(progress.searches()).append(progress.searches() == 1 ? " search, " : " searches, ")
                .append(Math.round(progress.elapsedMillis() / 1000.0)).append(" s)\n");
        int number = 0;
        for (ResearchSource source : sources) {
            text.append(++number).append(": ").append(source.title().isBlank() ? source.host() : source.title())
                    .append(" — ").append(source.url()).append('\n');
            if (!source.excerpt().isBlank()) text.append("   ").append(source.excerpt()).append('\n');
        }
        if (sources.isEmpty()) text.append("No sources.\n");
        if (!documents.isEmpty()) {
            text.append("Documents (not opened):\n");
            for (String document : documents) text.append("- ").append(document).append('\n');
        }
        for (String note : notes) text.append(note).append('\n');
        String hint = nextStep();
        if (!hint.isEmpty()) text.append("NEXT: ").append(hint).append('\n');
        return text.toString().trim();
    }

    private String nextStep() {
        return switch (stopReason) {
            case SUFFICIENT_EVIDENCE -> "web_open a source number's url to read it in full.";
            case CHALLENGE_REQUIRED -> "The search engines show a CAPTCHA. Solve it in the Argos browser window, then call web_research again.";
            case SEARCH_FAILED -> "The search failed technically. Try web_research again later.";
            case NO_SEARCH_RESULTS -> "Nothing was found. Try a shorter question with other words.";
            case NO_RELEVANT_PATHS -> sources.isEmpty()
                    ? "No relevant page found. Try other words, or web_search and web_open yourself."
                    : "No more relevant pages. Use the sources above.";
            case CANCELLED -> "The research was stopped.";
            default -> "Partial result: a limit was reached. Use the sources above or ask a narrower question.";
        };
    }
}
