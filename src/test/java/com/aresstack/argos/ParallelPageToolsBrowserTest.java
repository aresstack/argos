package com.aresstack.argos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in Camoufox test for parallel operations on persistent, identified tabs through MCP. */
class ParallelPageToolsBrowserTest {
    @TempDir Path temporary;

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void navigatesExistingTabsWithOverlapAndIsolatesPageFailures() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();

        ConcurrentMap<String, Long> bodyResponseStarted = new ConcurrentHashMap<>();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        site.createContext("/", exchange -> servePage(exchange, bodyResponseStarted));
        site.setExecutor(serverExecutor);
        site.start();

        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(),
                temporary.resolve("parallel-page-profile"), true).withNetwork(TestNetworks.LOOPBACK))) {
            String base = "http://localhost:" + site.getAddress().getPort();
            browser.navigate(base + "/initial");
            browser.newTab(base + "/initial");
            browser.newTab(base + "/initial");
            assertEquals("t3", browser.status().get("tab"));

            List<SyncToolSpecification> tools = new ArgosTools(browser).forProfile(ToolProfile.EXPERT);
            ObjectMapper mapper = new ObjectMapper();

            JsonNode navigation = mapper.readTree(toolText(callTool(tools, "browser_pages_navigate", Map.of(
                    "pages", List.of(
                            Map.of("tab", "t1", "url", base + "/slow-body?id=slow&delay=1800"),
                            Map.of("tab", "t2", "url", base + "/slow-body?id=fast&delay=200")),
                    "parallel", 2,
                    "timeoutMs", 5_000))));

            assertEquals(2, navigation.size());
            assertEquals("t1", navigation.get(0).get("tab").asText());
            assertEquals("t2", navigation.get(1).get("tab").asText());
            assertEquals("SUCCESS", navigation.get(0).get("outcome").asText(), navigation.toString());
            assertEquals("SUCCESS", navigation.get(1).get("outcome").asText(), navigation.toString());
            assertEquals("Slow page", navigation.get(0).get("title").asText());
            assertEquals("Fast page", navigation.get(1).get("title").asText());
            assertEquals("t3", browser.status().get("tab"), "Batch actions must not change the current tab");

            Long slowStarted = bodyResponseStarted.get("slow");
            Long fastStarted = bodyResponseStarted.get("fast");
            assertNotNull(slowStarted, "The slow page response must have started");
            assertNotNull(fastStarted, "The fast page response must have started");
            assertTrue(Math.abs(slowStarted - fastStarted) < TimeUnit.SECONDS.toNanos(1),
                    "Page bodies should load concurrently after their response commits");

            JsonNode snapshots = mapper.readTree(toolText(callTool(tools, "browser_pages_snapshot",
                    Map.of("tabs", List.of("t1", "t2"), "maxCharacters", 4_000))));
            assertTrue(snapshots.get(0).get("snapshot").get("text").asText().contains("Slow page"));
            assertTrue(snapshots.get(1).get("snapshot").get("text").asText().contains("Fast page"));

            JsonNode inspections = mapper.readTree(toolText(callTool(tools, "browser_pages_inspect",
                    Map.of("tabs", List.of("t1", "t2"), "selector", "h1"))));
            assertTrue(inspections.get(0).get("inspection").toString().contains("Slow page"));
            assertTrue(inspections.get(1).get("inspection").toString().contains("Fast page"));

            JsonNode partialInspection = mapper.readTree(toolText(callTool(tools, "browser_pages_inspect",
                    Map.of("tabs", List.of("t1", "t9"), "selector", "h1"))));
            assertTrue(partialInspection.get(0).has("inspection"), partialInspection.toString());
            assertTrue(partialInspection.get(1).has("error"), partialInspection.toString());

            JsonNode timedOutBatch = mapper.readTree(toolText(callTool(tools, "browser_pages_navigate", Map.of(
                    "pages", List.of(
                            Map.of("tab", "t3", "url", base + "/slow-head?id=timeout&delay=1500"),
                            Map.of("tab", "t2", "url", base + "/slow-body?id=survivor&delay=100")),
                    "parallel", 2,
                    "timeoutMs", 500))));
            assertEquals("FAILED", timedOutBatch.get(0).get("outcome").asText(), timedOutBatch.toString());
            assertEquals("t3", timedOutBatch.get(0).get("tab").asText());
            assertEquals("SUCCESS", timedOutBatch.get(1).get("outcome").asText(), timedOutBatch.toString());
            assertEquals("Survivor page", timedOutBatch.get(1).get("title").asText());
        } finally {
            site.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private static void servePage(HttpExchange exchange, ConcurrentMap<String, Long> responseStarted) {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = queryParameters(exchange.getRequestURI().getRawQuery());
        String id = query.getOrDefault("id", "");
        int delayMillis = parseDelay(query.get("delay"));
        boolean slowHeaders = path.equals("/slow-head");
        String title = switch (id) {
            case "slow" -> "Slow page";
            case "fast" -> "Fast page";
            case "survivor" -> "Survivor page";
            default -> "Initial page";
        };
        byte[] body = ("<html><head><title>" + title + "</title></head><body><h1>" + title
                + "</h1></body></html>").getBytes(StandardCharsets.UTF_8);
        try {
            if (slowHeaders) Thread.sleep(delayMillis);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            if (!id.isBlank() && !slowHeaders) responseStarted.put(id, System.nanoTime());
            if (!slowHeaders && delayMillis > 0) Thread.sleep(delayMillis);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The browser may cancel a navigation after its configured timeout.
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> queryParameters(String query) {
        if (query == null || query.isBlank()) return Map.of();
        return java.util.Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> pair[0], pair -> pair.length == 2 ? pair[1] : "",
                        (first, ignored) -> first));
    }

    private static int parseDelay(String value) {
        try {
            return Math.max(0, Math.min(5_000, Integer.parseInt(value)));
        } catch (RuntimeException malformed) {
            return 0;
        }
    }

    private static CallToolResult callTool(List<SyncToolSpecification> tools, String name,
                                           Map<String, Object> arguments) {
        SyncToolSpecification specification = tools.stream()
                .filter(candidate -> candidate.tool().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown test tool: " + name));
        return specification.callHandler().apply(null, new CallToolRequest(name, arguments));
    }

    private static String toolText(CallToolResult result) {
        assertFalse(result.isError(), result.content().toString());
        if (result.content().isEmpty()) return "";
        if (result.content().get(0) instanceof TextContent text) return text.text();
        return result.content().get(0).toString();
    }
}
