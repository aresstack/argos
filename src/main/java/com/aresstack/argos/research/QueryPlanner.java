package com.aresstack.argos.research;

import java.util.List;

/**
 * Port: which search queries a research question becomes. The default sends the question verbatim
 * (AskAI: the engine always receives the user's own text); query expansion by a model fits here.
 */
@FunctionalInterface
public interface QueryPlanner {
    QueryPlanner VERBATIM = question -> List.of(question.trim());

    List<String> plan(String question) throws Exception;
}
