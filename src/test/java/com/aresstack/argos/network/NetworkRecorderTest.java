package com.aresstack.argos.network;

import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Timing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.aresstack.argos.network.SecretMasker.MASK;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkRecorderTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Test void summarizesEachRequestOnceWithIdTimingAndMime() {
        NetworkRecorder recorder = recorder(false);
        Request request = request("GET", "https://example.org/api/items?token=secret&q=1", "fetch", null);
        Response response = response(request, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        Timing timing = new Timing();
        timing.domainLookupStart = 1; timing.domainLookupEnd = 3;
        timing.connectStart = -1; timing.connectEnd = -1; timing.secureConnectionStart = -1;
        timing.requestStart = 4; timing.responseStart = 20; timing.responseEnd = 25.4;
        when(request.timing()).thenReturn(timing);

        recorder.requestStarted(request);
        recorder.responseReceived(response);
        recorder.requestFinished(request);

        List<Map<String, Object>> rows = recorder.summary(null);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("n1", row.get("id"));
        assertEquals("response", row.get("kind"));
        assertEquals("https://example.org/api/items", row.get("url"));
        assertEquals(200, row.get("status"));
        assertEquals("application/json", row.get("mime"));
        assertEquals(25L, row.get("ms"));
        assertEquals(NOW.toString(), row.get("at"));

        Map<String, Object> detail = recorder.detail("n1", false);
        assertEquals("https://example.org/api/items?token=" + MASK + "&q=1", detail.get("url"));
        @SuppressWarnings("unchecked")
        Map<String, Double> phases = (Map<String, Double>) detail.get("timing");
        assertEquals(2.0, phases.get("dns"));
        assertEquals(-1.0, phases.get("connect"));
        assertEquals(16.0, phases.get("wait"));
        assertFalse(detail.containsKey("body"));
        verify(response, never()).body();
    }

    @Test void filtersByTypeMimeOrState() {
        NetworkRecorder recorder = recorder(false);
        Request page = request("GET", "https://example.org/", "document", null);
        recorder.requestStarted(page);
        recorder.responseReceived(response(page, 200, "text/html", "<p>hi</p>"));
        recorder.requestFinished(page);
        Request broken = request("GET", "https://cdn.example.org/app.js", "script", null);
        when(broken.failure()).thenReturn("NS_ERROR_CONNECTION_REFUSED");
        recorder.requestStarted(broken);
        recorder.requestFailed(broken);

        assertEquals(List.of("n2"), ids(recorder.summary("failed")));
        assertEquals(List.of("n1"), ids(recorder.summary("TEXT/HTML")));
        assertEquals(2, recorder.summary("  ").size());
        assertEquals("NS_ERROR_CONNECTION_REFUSED", recorder.detail("n2", true).get("failure"));
        assertEquals("request failed", recorder.detail("n2", true).get("bodyUnavailable"));
    }

    @Test void loadsMaskedTextBodyAndFullHeadersOnDemand() {
        NetworkRecorder recorder = recorder(false);
        Request request = request("POST", "https://example.org/login", "xhr",
                "user=ada&password=hunter2");
        when(request.headers()).thenReturn(Map.of("content-type", "application/x-www-form-urlencoded"));
        when(request.allHeaders()).thenReturn(Map.of("content-type", "application/x-www-form-urlencoded",
                "cookie", "sid=abc"));
        Response response = response(request, 200, "application/json",
                "{\"access_token\":\"eyJ\",\"name\":\"Ada\"}");
        when(response.allHeaders()).thenReturn(Map.of("content-type", "application/json",
                "set-cookie", "sid=new; Path=/"));
        recorder.requestStarted(request);
        recorder.responseReceived(response);
        recorder.requestFinished(request);

        Map<String, Object> detail = recorder.detail("n1", true);
        assertEquals("user=ada&password=" + MASK, detail.get("postData"));
        assertEquals("{\"access_token\":\"" + MASK + "\",\"name\":\"Ada\"}", detail.get("body"));
        assertEquals(Map.of("content-type", "application/x-www-form-urlencoded", "cookie", "sid=" + MASK),
                detail.get("requestHeaders"));
        @SuppressWarnings("unchecked")
        Map<String, String> responseHeaders = (Map<String, String>) detail.get("responseHeaders");
        assertEquals("sid=" + MASK + "; Path=/", responseHeaders.get("set-cookie"));

        recorder.detail("n1", true);
        verify(response, times(1)).body();
    }

    @Test void masksLargeBodiesBeforeTruncating() {
        NetworkRecorder recorder = recorder(false);
        String padding = "x".repeat(NetworkRecorder.MAX_BODY_CHARACTERS - 20);
        String secret = "s".repeat(100);
        Request request = request("GET", "https://example.org/big", "fetch", null);
        Response response = response(request, 200, "application/json",
                "{\"filler\":\"" + padding + "\",\"password\":\"" + secret + "\"}");
        recorder.requestStarted(request);
        recorder.responseReceived(response);
        recorder.requestFinished(request);

        Map<String, Object> detail = recorder.detail("n1", true);
        assertEquals(true, detail.get("bodyTruncated"));
        assertFalse(detail.get("body").toString().contains("sssss"));
    }

    @Test void listsPageDataResponsesWithShapeNewestFirst() {
        NetworkRecorder recorder = recorder(false);
        Object[][] exchanges = {
                {"https://example.org/api/search?q=x", "fetch", "application/json",
                        "{\"results\":[{\"t\":1},{\"t\":2}],\"total\":2,\"token\":\"secret\"}"},
                {"https://example.org/style.css", "stylesheet", "text/css", "body{}"},
                {"https://example.org/api/facets", "xhr", "application/vnd.api+json", "[1,2,3]"}};
        for (Object[] exchange : exchanges) {
            Request request = request("GET", (String) exchange[0], (String) exchange[1], null);
            recorder.requestStarted(request);
            recorder.responseReceived(response(request, 200, (String) exchange[2], (String) exchange[3]));
            recorder.requestFinished(request);
        }

        List<Map<String, Object>> rows = recorder.jsonResponses(null, 20);
        assertEquals(List.of("n3", "n1"), ids(rows));
        assertEquals("array[3]", rows.get(0).get("shape"));
        assertEquals("{results[2], total, token}", rows.get(1).get("shape"));
        assertEquals("https://example.org/api/search", rows.get(1).get("url"));
        assertEquals(List.of("n1"), ids(recorder.jsonResponses("search", 20)));
        assertFalse(recorder.detail("n1", true).get("body").toString().contains("secret"));
    }

    @Test void jsonListingFetchesOnlyASmallBudgetPerCall() {
        NetworkRecorder recorder = recorder(false);
        List<Response> responses = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            responses.add(finished(recorder, "https://example.org/api/" + i, "fetch", "application/json", "{\"i\":" + i + "}"));
        }
        List<Map<String, Object>> first = recorder.jsonResponses(null, 50);
        assertEquals(NetworkRecorder.MAX_JSON_RESPONSES, 20);
        assertEquals(12, first.size());
        long loaded = first.stream().filter(row -> row.containsKey("shape")).count();
        assertEquals(NetworkRecorder.AUTOMATIC_FETCHES_PER_TASK, loaded);
        assertTrue(first.get(11).get("bodyUnavailable").toString().contains("not loaded yet"));
        int calls = 0;
        for (Response response : responses) calls += mockingDetails(response).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("body")).count();
        assertEquals(NetworkRecorder.AUTOMATIC_FETCHES_PER_TASK, calls);

        // Already loaded bodies are cached, so the next call continues with the remaining ones.
        long secondLoaded = recorder.jsonResponses(null, 50).stream().filter(row -> row.containsKey("shape")).count();
        assertEquals(2L * NetworkRecorder.AUTOMATIC_FETCHES_PER_TASK, secondLoaded);
    }

    @Test void slowBodyFetchSuspendsAutomaticLoadingButNotExplicitDetail() {
        long[] now = {0};
        NetworkRecorder recorder = new NetworkRecorder(new SecretMasker(), true, () -> NOW, () -> now[0]);
        Response slow = finished(recorder, "https://example.org/api/slow", "fetch", "application/json", "{\"a\":1}");
        when(slow.body()).thenAnswer(invocation -> {
            now[0] += (NetworkRecorder.SLOW_FETCH_MILLIS + 1) * 1_000_000;
            return "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        });
        Response next = finished(recorder, "https://example.org/api/next", "fetch", "application/json", "{\"b\":2}");

        recorder.captureQueuedBodies();
        verify(slow, times(1)).body();
        verify(next, never()).body();
        assertTrue(recorder.jsonResponses(null, 10).get(0).get("bodyUnavailable").toString().contains("paused"));
        verify(next, never()).body();

        assertEquals("{\"b\":2}", recorder.detail("n2", true).get("body"));
        recorder.clear();
        Response fresh = finished(recorder, "https://example.org/api/fresh", "fetch", "application/json", "{}");
        recorder.captureQueuedBodies();
        verify(fresh, times(1)).body();
    }

    @Test void neverFetchesOversizedEmptyOrAbortedBodies() {
        NetworkRecorder recorder = recorder(false);
        Request big = request("GET", "https://example.org/huge.json", "fetch", null);
        Response bigResponse = response(big, 200, "application/json", "{}");
        when(bigResponse.headers()).thenReturn(Map.of("content-type", "application/json",
                "content-length", String.valueOf(NetworkRecorder.MAX_FETCH_BYTES + 1)));
        recorder.requestStarted(big);
        recorder.responseReceived(bigResponse);
        recorder.requestFinished(big);

        Request empty = request("GET", "https://example.org/none", "fetch", null);
        Response emptyResponse = response(empty, 204, "application/json", "");
        recorder.requestStarted(empty);
        recorder.responseReceived(emptyResponse);
        recorder.requestFinished(empty);

        // Headers arrived, then the transfer was aborted: never a finished download.
        Request aborted = request("GET", "https://example.org/aborted", "fetch", null);
        Response abortedResponse = response(aborted, 200, "application/json", "{\"partial\":");
        when(aborted.failure()).thenReturn("NS_BINDING_ABORTED");
        recorder.requestStarted(aborted);
        recorder.responseReceived(abortedResponse);
        recorder.requestFailed(aborted);

        Request loading = request("GET", "https://example.org/stream", "fetch", null);
        Response loadingResponse = response(loading, 200, "application/json", "{}");
        recorder.requestStarted(loading);
        recorder.responseReceived(loadingResponse);

        assertTrue(recorder.detail("n1", true).get("bodyUnavailable").toString().startsWith("too large"));
        assertEquals("no body (status 204)", recorder.detail("n2", true).get("bodyUnavailable"));
        assertEquals("request failed", recorder.detail("n3", true).get("bodyUnavailable"));
        assertEquals("still loading", recorder.detail("n4", true).get("bodyUnavailable"));
        for (Response response : List.of(bigResponse, emptyResponse, abortedResponse, loadingResponse)) {
            verify(response, never()).body();
        }
    }

    @Test void neverFetchesBinaryBodiesAndReportsWhy() {
        NetworkRecorder recorder = recorder(false);
        Request request = request("GET", "https://example.org/paper.pdf", "document", null);
        Response response = response(request, 200, "application/pdf", "%PDF");
        recorder.requestStarted(request);
        recorder.responseReceived(response);
        recorder.requestFinished(request);

        assertTrue(recorder.detail("n1", true).get("bodyUnavailable").toString().contains("application/pdf"));
        verify(response, never()).body();
    }

    @Test void eagerCaptureWaitsUntilOutsideEventHandlersAndSkipsStaticAssets() {
        NetworkRecorder recorder = recorder(true);
        Request api = request("GET", "https://example.org/api", "fetch", null);
        Response apiResponse = response(api, 200, "application/json", "{\"a\":1}");
        Request image = request("GET", "https://example.org/logo.svg", "image", null);
        Response imageResponse = response(image, 200, "image/svg+xml", "<svg/>");
        for (Object[] pair : new Object[][] {{api, apiResponse}, {image, imageResponse}}) {
            recorder.requestStarted((Request) pair[0]);
            recorder.responseReceived((Response) pair[1]);
            recorder.requestFinished((Request) pair[0]);
        }
        verify(apiResponse, never()).body();

        recorder.captureQueuedBodies();
        verify(apiResponse, times(1)).body();
        verify(imageResponse, never()).body();
        assertEquals("{\"a\":1}", recorder.detail("n1", false).get("body"));
    }

    @Test void linksRedirectsAndEvictsOldestEntries() {
        NetworkRecorder recorder = recorder(false);
        Request first = request("GET", "http://example.org/", "document", null);
        recorder.requestStarted(first);
        Request second = request("GET", "https://example.org/", "document", null);
        when(second.redirectedFrom()).thenReturn(first);
        recorder.requestStarted(second);
        assertEquals("n1", recorder.detail("n2", false).get("redirectedFrom"));

        for (int i = 0; i < NetworkRecorder.MAX_ENTRIES; i++) {
            recorder.requestStarted(request("GET", "https://example.org/" + i, "image", null));
        }
        assertEquals(NetworkRecorder.MAX_ENTRIES, recorder.summary(null).size());
        IllegalArgumentException gone = assertThrows(IllegalArgumentException.class, () -> recorder.detail("n1", false));
        assertTrue(gone.getMessage().contains("n1"));

        recorder.clear();
        assertTrue(recorder.summary(null).isEmpty());
    }

    @Test void exportsMaskedHar() {
        NetworkRecorder recorder = recorder(false);
        Request request = request("GET", "https://example.org/a?q=x&api_key=k", "document", null);
        when(request.headers()).thenReturn(Map.of("authorization", "Bearer abcdefghijkl"));
        recorder.requestStarted(request);
        recorder.responseReceived(response(request, 302, "text/html", ""));
        recorder.requestFinished(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> log = (Map<String, Object>) recorder.har(10, "test").get("log");
        assertEquals("1.2", log.get("version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = ((List<Map<String, Object>>) log.get("entries")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> harRequest = (Map<String, Object>) entry.get("request");
        assertEquals(List.of(Map.of("name", "q", "value", "x"), Map.of("name", "api_key", "value", MASK)),
                harRequest.get("queryString"));
        assertEquals(List.of(Map.of("name", "authorization", "value", MASK)), harRequest.get("headers"));
        assertEquals("n1", entry.get("_argosId"));
        assertFalse(String.valueOf(recorder.har(10, "test")).contains("abcdefghijkl"));
    }

    private static NetworkRecorder recorder(boolean eagerBodies) {
        return new NetworkRecorder(new SecretMasker(), eagerBodies, () -> NOW, System::nanoTime);
    }

    private static Response finished(NetworkRecorder recorder, String url, String type, String mime, String body) {
        Request request = request("GET", url, type, null);
        Response response = response(request, 200, mime, body);
        recorder.requestStarted(request);
        recorder.responseReceived(response);
        recorder.requestFinished(request);
        return response;
    }

    private static Request request(String method, String url, String type, String postData) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn(method);
        when(request.url()).thenReturn(url);
        when(request.resourceType()).thenReturn(type);
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(postData);
        return request;
    }

    private static Response response(Request request, int status, String contentType, String body) {
        Response response = mock(Response.class);
        when(response.request()).thenReturn(request);
        when(response.status()).thenReturn(status);
        when(response.statusText()).thenReturn(status == 200 ? "OK" : "");
        when(response.headers()).thenReturn(Map.of("content-type", contentType));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private static List<String> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (String) row.get("id")).toList();
    }
}
