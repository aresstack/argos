package com.aresstack.argos.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The single-file HTML collector of the OpenIPC cam tool ({@code PageArchiver::inlineJsonJs}, {@code kCaptureJs})
 * as a Java resource: clones the rendered DOM, removes scripts and (module)preload links, inlines fetchable
 * stylesheets, CSS {@code url(...)} resources and images as data URLs (at most 8 MiB each) and makes plain links
 * absolute. Only the inline mode is used; the split mode with resource folders is never built.
 * <p>
 * The function runs as ONE transient Playwright evaluation and returns its JSON result; it declares no globals,
 * adds no element to the live document and leaves nothing behind. It does fetch the page's resources again from
 * inside the page (as the original does), see docs/search-history.md for that trade-off.
 */
public final class HtmlCollector {
    /** A collector that runs longer than this answers "" (no archive) rather than holding the browser. */
    public static final int DEFAULT_TIMEOUT_MILLIS = 15_000;
    /** A result above this size is not kept (the 8 MiB per-resource limit of the original still applies). */
    static final int MAX_RESULT_CHARACTERS = 64 * 1024 * 1024;
    private static final String RESOURCE = "page-capture.js";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = load();

    private HtmlCollector() {
    }

    /** The archived page as the collector reports it. */
    public record Collected(String url, String title, String html) { }

    /** Runs the collector function in the page; the browser adapter decides which page. */
    @FunctionalInterface
    public interface PageEvaluator {
        Object evaluate(String function) throws Exception;
    }

    /** The collector source with the inline-JSON settings of {@code inlineJsonJs()}. */
    public static String collectorFunction() {
        return SOURCE.replace("__CB__", "argos")
                .replace("__SPLIT__", "false")
                .replace("__JSON__", "true")
                .replace("__MARKER__", "");
    }

    /** The function handed to evaluate(): the collector, bounded by {@code timeoutMillis}. */
    public static String evaluation(int timeoutMillis) {
        return "async () => { const collect = " + collectorFunction().strip() + ";\n"
                + "return await Promise.race([collect(), new Promise(done => setTimeout(() => done(\"\"), "
                + Math.max(1_000, timeoutMillis) + "))]); }";
    }

    /** Runs the collector; empty when it timed out, failed in the page or returned no HTML. */
    public static Optional<Collected> collect(PageEvaluator page, int timeoutMillis) throws Exception {
        Object value = page.evaluate(evaluation(timeoutMillis));
        return parse(value);
    }

    static Optional<Collected> parse(Object value) throws IOException {
        if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_RESULT_CHARACTERS) {
            return Optional.empty();
        }
        JsonNode result = JSON.readTree(text);
        String html = result.path("html").asText("");
        if (html.isBlank()) return Optional.empty();
        return Optional.of(new Collected(result.path("url").asText(""), result.path("title").asText(""), html));
    }

    private static String load() {
        try (InputStream in = HtmlCollector.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing resource " + RESOURCE);
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // The leading comment block documents the port; the evaluated value starts at the function.
            return source.substring(source.indexOf("async function"));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
