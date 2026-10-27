package com.aresstack.argos.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Timing;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Observes page traffic purely through Playwright's protocol events (WebDriver BiDi
 * network module). Nothing is injected into the page, so recording is invisible to it.
 *
 * <p>Not thread-safe: every method must run on the browser driver thread, which is also
 * the thread Playwright dispatches events on.</p>
 */
public final class NetworkRecorder {
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_BODY_CHARACTERS = 32_000;
    private static final int MAX_POST_DATA_CHARACTERS = 8_000;
    private static final int MASKING_INPUT_LIMIT = 2_000_000;
    // Bodies announced larger than this are never fetched over the protocol.
    static final long MAX_FETCH_BYTES = 2_000_000;
    // Automatic fetching (eager capture, JSON listing) gets a small budget per browser task,
    // so a heavy page can never keep the single driver thread busy for long.
    static final int AUTOMATIC_FETCHES_PER_TASK = 5;
    static final long AUTOMATIC_FETCH_BUDGET_MILLIS = 2_000;
    // One fetch this slow suspends automatic fetching until clear(); explicit detail calls still work.
    static final long SLOW_FETCH_MILLIS = 3_000;
    public static final int MAX_JSON_RESPONSES = 20;
    // Eager body capture is limited to the exchanges that carry page data.
    private static final Set<String> EAGER_BODY_TYPES = Set.of("document", "xhr", "fetch");

    private static final int MAX_JSON_KEYS = 12;
    private final ObjectMapper json = new ObjectMapper();
    private final SecretMasker masker;
    private final boolean captureBodies;
    private final Supplier<Instant> clock;
    private final LongSupplier nanos;
    private boolean automaticFetchSuspended;
    private final Deque<NetworkEntry> entries = new ArrayDeque<>();
    private final Map<String, NetworkEntry> byId = new LinkedHashMap<>();
    private final Map<Request, NetworkEntry> byRequest = new IdentityHashMap<>();
    private final Deque<NetworkEntry> pendingBodies = new ArrayDeque<>();
    private long sequence;

    public NetworkRecorder(SecretMasker masker, boolean captureBodies) {
        this(masker, captureBodies, Instant::now, System::nanoTime);
    }

    NetworkRecorder(SecretMasker masker, boolean captureBodies, Supplier<Instant> clock, LongSupplier nanos) {
        this.masker = masker;
        this.captureBodies = captureBodies;
        this.clock = clock;
        this.nanos = nanos;
    }

    public void attach(Page page) {
        page.onRequest(request -> requestStarted(request, page));
        page.onResponse(this::responseReceived);
        page.onRequestFinished(this::requestFinished);
        page.onRequestFailed(this::requestFailed);
    }

    void requestStarted(Request request) {
        requestStarted(request, null);
    }

    private void requestStarted(Request request, Page ownerPage) {
        if (entries.size() >= MAX_ENTRIES) evictOldest();
        NetworkEntry previous = request.redirectedFrom() == null ? null : byRequest.get(request.redirectedFrom());
        String postData = request.postData();
        Map<String, String> headers = masker.headers(request.headers());
        NetworkEntry entry = new NetworkEntry("n" + (++sequence), clock.get(), nanos.getAsLong(),
                request.method(), masker.urlWithoutQuery(request.url()), masker.url(request.url()),
                request.resourceType(), headers,
                postData == null ? null : truncate(masker.body(postData, header(headers, "content-type")),
                        MAX_POST_DATA_CHARACTERS),
                previous == null ? null : previous.id, request, ownerPage);
        entries.addLast(entry);
        byId.put(entry.id, entry);
        byRequest.put(request, entry);
    }

    void responseReceived(Response response) {
        NetworkEntry entry = byRequest.get(response.request());
        if (entry == null) return;
        entry.response = response;
        entry.state = NetworkEntry.State.RESPONSE;
        entry.status = response.status();
        entry.statusText = response.statusText() == null ? "" : response.statusText();
        entry.responseHeaders = masker.headers(response.headers());
        entry.mimeType = mimeType(header(entry.responseHeaders, "content-type"));
    }

    void requestFinished(Request request) {
        NetworkEntry entry = byRequest.get(request);
        if (entry == null) return;
        if (entry.state != NetworkEntry.State.FAILED) entry.state = NetworkEntry.State.FINISHED;
        complete(entry, request);
        if (captureBodies && entry.response != null && EAGER_BODY_TYPES.contains(entry.resourceType)) {
            // Response.body() is a protocol round trip: never issue it inside an event handler.
            pendingBodies.addLast(entry);
        }
    }

    void requestFailed(Request request) {
        NetworkEntry entry = byRequest.get(request);
        if (entry == null) return;
        entry.state = NetworkEntry.State.FAILED;
        String failure = request.failure();
        entry.failure = failure == null ? "unknown" : masker.text(failure);
        complete(entry, request);
    }

    /** Fetches bodies queued by eager capture; call outside event handlers. */
    public void captureQueuedBodies() {
        FetchBudget budget = new FetchBudget();
        while (!pendingBodies.isEmpty() && budget.allowsAnother()) {
            NetworkEntry entry = pendingBodies.removeFirst();
            if (byId.get(entry.id) == entry && entry.body == null && entry.bodyUnavailable == null) {
                budget.fetch(entry);
            }
        }
        // Whatever is left waits for the next browser task.
    }

    /**
     * The page's own data responses (JSON from XHR/fetch and similar), newest first, with a
     * shape preview so a model can pick the right one before reading it with detail().
     * Bodies come from the browser's network data collector, never from in-page scripts.
     */
    public List<Map<String, Object>> jsonResponses(String filter, int limit) {
        return jsonResponses(filter, limit, null);
    }

    public List<Map<String, Object>> jsonResponses(String filter, int limit, Page ownerPage) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        int count = Math.min(MAX_JSON_RESPONSES, Math.max(1, limit));
        FetchBudget budget = new FetchBudget();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var iterator = entries.descendingIterator(); iterator.hasNext() && rows.size() < count; ) {
            NetworkEntry entry = iterator.next();
            if (ownerPage != null && entry.ownerPage != ownerPage) continue;
            if (entry.state != NetworkEntry.State.FINISHED || !entry.mimeType.contains("json")) continue;
            if (!needle.isEmpty() && !entry.url.toLowerCase(Locale.ROOT).contains(needle)) continue;
            boolean deferred = false;
            if (entry.body == null && entry.bodyUnavailable == null) {
                if (budget.allowsAnother()) budget.fetch(entry);
                else deferred = true;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id);
            row.put("url", entry.listUrl);
            row.put("status", entry.status);
            row.put("type", entry.resourceType);
            if (entry.body == null) {
                row.put("bodyUnavailable", entry.bodyUnavailable != null ? entry.bodyUnavailable
                        : deferred ? automaticFetchSuspended
                                ? "automatic loading paused after a slow response; use browser_network_detail"
                                : "not loaded yet; call again or use browser_network_detail"
                        : "still loading");
            } else {
                row.put("bytes", entry.bodyBytes);
                row.put("shape", jsonShape(entry.body, entry.bodyTruncated));
            }
            rows.add(row);
        }
        return rows;
    }

    /** Compact one-line-per-request view; same base fields as the original browser_network. */
    public List<Map<String, Object>> summary(String filter) {
        return summary(filter, null);
    }

    public List<Map<String, Object>> summary(String filter, Page ownerPage) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NetworkEntry entry : entries) {
            if (ownerPage != null && entry.ownerPage != ownerPage) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id);
            row.put("at", entry.startedAt.toString());
            row.put("kind", entry.kind());
            row.put("method", entry.method);
            row.put("url", entry.listUrl);
            row.put("status", entry.status);
            row.put("type", entry.resourceType);
            if (!entry.mimeType.isEmpty()) row.put("mime", entry.mimeType);
            if (entry.durationMillis != null) row.put("ms", entry.durationMillis);
            if (needle.isEmpty() || matches(row, needle)) rows.add(row);
        }
        return rows;
    }

    public Map<String, Object> detail(String id, boolean includeBody) {
        return detail(id, includeBody, null);
    }

    public Map<String, Object> detail(String id, boolean includeBody, Page ownerPage) {
        NetworkEntry entry = byId.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown network id: " + id
                    + " (ids come from browser_network; only the latest " + MAX_ENTRIES + " are kept)");
        }
        if (ownerPage != null && entry.ownerPage != ownerPage) {
            throw new IllegalArgumentException("Network id " + id + " does not belong to the requested tab.");
        }
        boolean settled = entry.state == NetworkEntry.State.FINISHED || entry.state == NetworkEntry.State.FAILED;
        if (settled && !entry.fullHeadersLoaded) loadFullHeaders(entry);
        if (includeBody && entry.body == null && entry.bodyUnavailable == null) loadBody(entry);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", entry.id);
        detail.put("startedAt", entry.startedAt.toString());
        detail.put("kind", entry.kind());
        detail.put("method", entry.method);
        detail.put("url", entry.url);
        detail.put("type", entry.resourceType);
        detail.put("status", entry.status);
        detail.put("statusText", entry.statusText);
        detail.put("mime", entry.mimeType);
        if (entry.durationMillis != null) detail.put("durationMs", entry.durationMillis);
        if (entry.timing != null) detail.put("timing", entry.timing);
        if (entry.redirectedFromId != null) detail.put("redirectedFrom", entry.redirectedFromId);
        if (entry.failure != null) detail.put("failure", entry.failure);
        detail.put("requestHeaders", entry.fullRequestHeaders == null ? entry.requestHeaders : entry.fullRequestHeaders);
        if (entry.postData != null) detail.put("postData", entry.postData);
        detail.put("responseHeaders", entry.responseHeaders);
        if (entry.body != null) {
            detail.put("body", entry.body);
            detail.put("bodyBytes", entry.bodyBytes);
            if (entry.bodyTruncated) detail.put("bodyTruncated", true);
        } else if (includeBody) {
            detail.put("bodyUnavailable", entry.bodyUnavailable == null ? "still loading" : entry.bodyUnavailable);
        }
        return detail;
    }

    /** HAR 1.2 export of the newest {@code limit} entries; contains only masked data. */
    public Map<String, Object> har(int limit, String creatorVersion) {
        return har(limit, creatorVersion, null);
    }

    public Map<String, Object> har(int limit, String creatorVersion, Page ownerPage) {
        List<NetworkEntry> selected = entries.stream()
                .filter(entry -> ownerPage == null || entry.ownerPage == ownerPage)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int count = Math.min(MAX_ENTRIES, Math.max(1, limit));
        if (selected.size() > count) selected = selected.subList(selected.size() - count, selected.size());
        List<Map<String, Object>> harEntries = new ArrayList<>();
        for (NetworkEntry entry : selected) harEntries.add(harEntry(entry));
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("version", "1.2");
        log.put("creator", Map.of("name", "Argos", "version", creatorVersion));
        log.put("pages", List.of());
        log.put("entries", harEntries);
        return Map.of("log", log);
    }

    public void clear() {
        entries.clear();
        byId.clear();
        byRequest.clear();
        pendingBodies.clear();
        automaticFetchSuspended = false;
    }

    private void complete(NetworkEntry entry, Request request) {
        Timing timing = request.timing();
        if (timing != null) {
            entry.timing = timingPhases(timing);
            if (timing.responseEnd > 0) entry.durationMillis = Math.round(timing.responseEnd);
        }
        if (entry.durationMillis == null) {
            entry.durationMillis = (nanos.getAsLong() - entry.startedNanos) / 1_000_000;
        }
    }

    /**
     * Event payloads carry provisional headers only; cookies and other security headers
     * need a protocol round trip, which is made lazily and never inside an event handler.
     */
    private void loadFullHeaders(NetworkEntry entry) {
        entry.fullHeadersLoaded = true;
        try {
            entry.fullRequestHeaders = merged(entry.requestHeaders, masker.headers(entry.request.allHeaders()));
            if (entry.response != null) {
                entry.responseHeaders = merged(entry.responseHeaders, masker.headers(entry.response.allHeaders()));
            }
        } catch (RuntimeException unavailable) {
            // Keep the provisional headers recorded from the events.
        }
    }

    private void loadBody(NetworkEntry entry) {
        if (entry.state == NetworkEntry.State.FAILED) {
            entry.bodyUnavailable = "request failed";
            return;
        }
        if (entry.response == null || entry.state != NetworkEntry.State.FINISHED) {
            // Not cached: the body may still arrive, so a later detail call retries.
            return;
        }
        if (entry.status == 204 || entry.status == 304 || (entry.status >= 300 && entry.status < 400)
                || entry.method.equalsIgnoreCase("HEAD")) {
            entry.bodyUnavailable = "no body (status " + entry.status + ")";
            return;
        }
        Long announced = contentLength(entry.responseHeaders);
        if (announced != null && announced > MAX_FETCH_BYTES) {
            entry.bodyUnavailable = "too large (" + announced + " bytes)";
            return;
        }
        if (!isTextual(entry.mimeType)) {
            entry.bodyUnavailable = "binary content (" + (entry.mimeType.isEmpty() ? "unknown type" : entry.mimeType)
                    + "); use the document tools instead";
            return;
        }
        try {
            byte[] bytes = entry.response.body();
            String decoded = new String(bytes, charset(header(entry.responseHeaders, "content-type")));
            entry.bodyBytes = bytes.length;
            // Mask the whole body first: a cut inside a secret would defeat the masking patterns.
            // Only absurdly large bodies are pre-cut, far beyond the visible output limit.
            String bounded = decoded.length() > MASKING_INPUT_LIMIT ? decoded.substring(0, MASKING_INPUT_LIMIT) : decoded;
            String masked = masker.body(bounded, entry.mimeType);
            entry.bodyTruncated = masked.length() > MAX_BODY_CHARACTERS;
            entry.body = entry.bodyTruncated ? masked.substring(0, MAX_BODY_CHARACTERS) : masked;
        } catch (RuntimeException unavailable) {
            // Bodies of evicted or redirected responses are gone from the browser cache.
            entry.bodyUnavailable = "body no longer available";
        }
    }

    private Map<String, Object> harEntry(NetworkEntry entry) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", entry.method);
        request.put("url", entry.url);
        request.put("httpVersion", "");
        request.put("cookies", List.of());
        request.put("headers", harHeaders(entry.fullRequestHeaders == null
                ? entry.requestHeaders : entry.fullRequestHeaders));
        request.put("queryString", queryString(entry.url));
        if (entry.postData != null) {
            request.put("postData", Map.of("mimeType", mimeType(header(entry.requestHeaders, "content-type")),
                    "text", entry.postData));
        }
        request.put("headersSize", -1);
        request.put("bodySize", -1);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("size", entry.bodyBytes == null ? -1 : entry.bodyBytes);
        content.put("mimeType", entry.mimeType);
        if (entry.body != null) content.put("text", entry.body);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", entry.status);
        response.put("statusText", entry.statusText);
        response.put("httpVersion", "");
        response.put("cookies", List.of());
        response.put("headers", harHeaders(entry.responseHeaders));
        response.put("content", content);
        String location = header(entry.responseHeaders, "location");
        response.put("redirectURL", location == null ? "" : location);
        response.put("headersSize", -1);
        response.put("bodySize", -1);

        Map<String, Object> har = new LinkedHashMap<>();
        har.put("startedDateTime", entry.startedAt.toString());
        har.put("time", entry.durationMillis == null ? -1 : entry.durationMillis);
        har.put("request", request);
        har.put("response", response);
        har.put("cache", Map.of());
        har.put("timings", harTimings(entry.timing));
        har.put("_argosId", entry.id);
        har.put("_resourceType", entry.resourceType);
        if (entry.failure != null) har.put("_failure", entry.failure);
        return har;
    }

    /** Per-task limit on automatic body fetches: count, cumulative time and a stall breaker. */
    private final class FetchBudget {
        private final long startedNanos = nanos.getAsLong();
        private int fetches;

        boolean allowsAnother() {
            return !automaticFetchSuspended && fetches < AUTOMATIC_FETCHES_PER_TASK
                    && elapsedMillis(startedNanos) < AUTOMATIC_FETCH_BUDGET_MILLIS;
        }

        void fetch(NetworkEntry entry) {
            fetches++;
            long before = nanos.getAsLong();
            loadBody(entry);
            if (elapsedMillis(before) >= SLOW_FETCH_MILLIS) automaticFetchSuspended = true;
        }
    }

    private long elapsedMillis(long sinceNanos) {
        return (nanos.getAsLong() - sinceNanos) / 1_000_000;
    }

    // Full headers extend the provisional ones; a header missing from the full set is kept.
    private static Map<String, String> merged(Map<String, String> provisional, Map<String, String> full) {
        Map<String, String> result = new LinkedHashMap<>(provisional);
        full.forEach((name, value) -> {
            result.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            result.put(name, value);
        });
        return result;
    }

    private static Long contentLength(Map<String, String> headers) {
        String value = header(headers, "content-length");
        if (value == null) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private String jsonShape(String body, boolean truncated) {
        try {
            JsonNode tree = json.readTree(body);
            if (tree.isArray()) return "array[" + tree.size() + "]";
            if (tree.isObject()) {
                List<String> keys = new ArrayList<>();
                tree.fieldNames().forEachRemaining(name -> {
                    if (keys.size() < MAX_JSON_KEYS) {
                        JsonNode child = tree.get(name);
                        keys.add(child.isArray() ? name + "[" + child.size() + "]" : name);
                    }
                });
                return "{" + String.join(", ", keys) + (tree.size() > MAX_JSON_KEYS ? ", …" : "") + "}";
            }
            return tree.getNodeType().name().toLowerCase(Locale.ROOT);
        } catch (Exception unparseable) {
            return truncated ? "json (truncated)" : "json (unparseable)";
        }
    }

    private static Map<String, Double> timingPhases(Timing timing) {
        Map<String, Double> phases = new LinkedHashMap<>();
        phases.put("dns", span(timing.domainLookupStart, timing.domainLookupEnd));
        phases.put("connect", span(timing.connectStart, timing.connectEnd));
        phases.put("ssl", span(timing.secureConnectionStart, timing.connectEnd));
        phases.put("wait", span(timing.requestStart, timing.responseStart));
        phases.put("receive", span(timing.responseStart, timing.responseEnd));
        return phases;
    }

    private static Map<String, Object> harTimings(Map<String, Double> phases) {
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("blocked", -1);
        timings.put("dns", phases == null ? -1 : phases.get("dns"));
        timings.put("connect", phases == null ? -1 : phases.get("connect"));
        timings.put("ssl", phases == null ? -1 : phases.get("ssl"));
        timings.put("send", 0);
        timings.put("wait", phases == null ? -1 : phases.get("wait"));
        timings.put("receive", phases == null ? -1 : phases.get("receive"));
        return timings;
    }

    // Playwright reports -1 for phases the browser did not measure (e.g. reused connections).
    private static double span(double start, double end) {
        return start < 0 || end < 0 || end < start ? -1 : Math.round((end - start) * 10) / 10.0;
    }

    private static List<Map<String, String>> harHeaders(Map<String, String> headers) {
        List<Map<String, String>> list = new ArrayList<>();
        headers.forEach((name, value) -> list.add(Map.of("name", name, "value", value)));
        return list;
    }

    private static List<Map<String, String>> queryString(String url) {
        List<Map<String, String>> list = new ArrayList<>();
        // Masked URLs contain "[REDACTED]", which java.net.URI rejects, so split by hand.
        int question = url.indexOf('?');
        String query = question < 0 ? "" : url.substring(question + 1);
        if (query.isEmpty()) return list;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            list.add(Map.of("name", decode(equals < 0 ? pair : pair.substring(0, equals)),
                    "value", equals < 0 ? "" : decode(pair.substring(equals + 1))));
        }
        return list;
    }

    private void evictOldest() {
        NetworkEntry oldest = entries.removeFirst();
        byId.remove(oldest.id);
        byRequest.remove(oldest.request);
    }

    private static boolean matches(Map<String, Object> row, String needle) {
        for (Object value : row.values()) {
            if (String.valueOf(value).toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) return header.getValue();
        }
        return null;
    }

    static String mimeType(String contentType) {
        if (contentType == null) return "";
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    static boolean isTextual(String mime) {
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
                || mime.contains("javascript") || mime.contains("ecmascript")
                || mime.equals("application/x-www-form-urlencoded") || mime.equals("image/svg+xml");
    }

    private static Charset charset(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(trimmed.substring(8).replace("\"", "").trim());
                    } catch (RuntimeException unsupported) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String truncate(String value, int limit) {
        return value.length() > limit ? value.substring(0, limit) + "[TRUNCATED]" : value;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }
}
