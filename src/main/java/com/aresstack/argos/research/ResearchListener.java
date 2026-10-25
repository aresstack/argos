package com.aresstack.argos.research;

/** Port: progress of a running research, e.g. MCP progress notifications. Must not throw. */
@FunctionalInterface
public interface ResearchListener {
    ResearchListener NONE = (progress, activity) -> { };

    void onProgress(ResearchProgress progress, String activity);
}
