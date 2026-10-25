package com.aresstack.argos.eval;

import java.util.List;

/** Port to a language model with tool calling. */
public interface ChatModel {
    ModelTurn complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception;

    /** Model name for the report, e.g. "qwen3:8b". */
    String name();

    /** Frees the model's server resources (memory) before another model runs; a no-op where not supported. */
    default void release() {
    }

    /** Raised for HTTP or protocol failures of the model endpoint; never contains the API key. */
    final class ModelException extends Exception {
        public ModelException(String message) {
            super(message);
        }

        public ModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
