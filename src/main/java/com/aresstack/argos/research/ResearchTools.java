package com.aresstack.argos.research;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/**
 * MCP adapter of the optional research orchestration: {@code web_research(question)} runs a whole
 * bounded research in one call and answers with numbered sources; {@code web_research_stop()} ends a
 * running one early. Both have at most one parameter, as small models need.
 */
public final class ResearchTools {
    private final ResearchService research;

    public ResearchTools(ResearchService research) {
        this.research = research;
    }

    public List<SyncToolSpecification> specifications() {
        ResearchBudget budget = research.budget();
        String researchDescription = "Research a question on the web in one step: searches, reads up to "
                + budget.maxPagesRead() + " pages, follows relevant links and returns numbered sources with a "
                + "short quote each. Takes up to " + budget.maxDuration().toSeconds() + " seconds. "
                + "Example: {\"question\": \"Which license does Camoufox use?\"}";
        return List.of(
                SyncToolSpecification.builder()
                        .tool(Tool.builder().name("web_research").description(researchDescription)
                                .inputSchema(new JsonSchema("object", Map.of("question", Map.of("type", "string",
                                        "description", "What you want to find out, as a short question")),
                                        List.of("question"), false, Map.of(), Map.of()))
                                .build())
                        .callHandler(this::runResearch).build(),
                SyncToolSpecification.builder()
                        .tool(Tool.builder().name("web_research_stop")
                                .description("Stop a running web_research early; it returns what it found so far.")
                                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of()))
                                .build())
                        .callHandler((exchange, request) -> text(research.stop()
                                ? "Stopping the running research." : "No research is running.", false))
                        .build());
    }

    private CallToolResult runResearch(McpSyncServerExchange exchange, CallToolRequest request) {
        Object question = request.arguments() == null ? null : request.arguments().get("question");
        if (!(question instanceof String text) || text.isBlank()) {
            return text("Missing string field: question", true);
        }
        try {
            ResearchReport report = research.research(text, progressNotifier(exchange, request));
            return text(report.format(), false);
        } catch (ResearchService.ResearchBusyException | IllegalArgumentException refused) {
            return text(refused.getMessage(), true);
        } catch (RuntimeException failure) {
            return text("Research failed: " + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage()), true);
        }
    }

    /** MCP progress notifications when the client asked for them (progressToken), otherwise nothing. */
    private ResearchListener progressNotifier(McpSyncServerExchange exchange, CallToolRequest request) {
        Object token = request.progressToken();
        if (exchange == null || token == null) return ResearchListener.NONE;
        double total = research.budget().maxPagesRead();
        return (progress, activity) -> exchange.progressNotification(new ProgressNotification(
                token, (double) progress.pagesRead(), total, activity));
    }

    private static CallToolResult text(String text, boolean error) {
        return CallToolResult.builder().isError(error).addTextContent(text).build();
    }
}
