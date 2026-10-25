package com.aresstack.argos.eval;

import java.util.List;
import java.util.Map;

/** Port to the tools under test (Argos over MCP in the real evaluation). */
public interface ToolGateway {
    List<ToolDefinition> tools();

    ToolOutcome call(String name, Map<String, Object> arguments);

    /** What a tool returned: the text the model gets to read, and whether the tool reported an error. */
    record ToolOutcome(String text, boolean error) {
    }
}
