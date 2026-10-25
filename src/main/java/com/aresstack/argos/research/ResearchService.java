package com.aresstack.argos.research;

import com.aresstack.argos.web.WebSearchPort;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Entry point of the optional research orchestration. Depends only on ports, so the browser core works
 * without it and it works without a model. One run at a time: a run drives the single shared browser page.
 */
public final class ResearchService {
    /** Thrown when a second run is requested while one is in progress. */
    public static final class ResearchBusyException extends Exception {
        ResearchBusyException() {
            super("A research is already running. Wait for it, or stop it with web_research_stop.");
        }
    }

    private final WebSearchPort search;
    private final ResearchPageReader reader;
    private final RelevanceScorer scorer;
    private final QueryPlanner planner;
    private final ResearchBudget budget;
    private final LongSupplier clock;
    private final ReentrantLock running = new ReentrantLock();
    private final AtomicReference<AtomicBoolean> currentCancellation = new AtomicReference<>();

    public ResearchService(WebSearchPort search, ResearchPageReader reader, ResearchBudget budget) {
        this(search, reader, new LexicalRelevanceScorer(), QueryPlanner.VERBATIM, budget, System::currentTimeMillis);
    }

    public ResearchService(WebSearchPort search, ResearchPageReader reader, RelevanceScorer scorer,
                           QueryPlanner planner, ResearchBudget budget, LongSupplier clock) {
        this.search = search;
        this.reader = reader;
        this.scorer = scorer;
        this.planner = planner;
        this.budget = budget;
        this.clock = clock;
    }

    public ResearchReport research(String question, ResearchListener listener) throws ResearchBusyException {
        if (question == null || QueryTerms.of(question).isEmpty()) {
            throw new IllegalArgumentException("The question needs at least one word of three or more letters.");
        }
        if (!running.tryLock()) throw new ResearchBusyException();
        AtomicBoolean cancelled = new AtomicBoolean();
        currentCancellation.set(cancelled);
        try {
            return new ResearchLoop(question, search, reader, scorer, planner, budget, clock,
                    () -> cancelled.get() || Thread.currentThread().isInterrupted(),
                    listener == null ? ResearchListener.NONE : listener).run();
        } finally {
            currentCancellation.set(null);
            running.unlock();
        }
    }

    /** Asks the running research to stop at its next gate; false when none is running. */
    public boolean stop() {
        AtomicBoolean cancelled = currentCancellation.get();
        if (cancelled == null) return false;
        cancelled.set(true);
        return true;
    }

    public ResearchBudget budget() {
        return budget;
    }
}
