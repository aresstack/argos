package com.aresstack.argos.network;

import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The durable content capture on the existing recorder (spec §5, Test D/E): metadata decides inside the event
 * handler, bodies are read over the protocol afterwards, within a bounded queue and budget, independent of
 * ARGOS_NETWORK_BODIES, and never as a new request.
 */
class NetworkContentCaptureTest {
    private final List<String> delivered = new ArrayList<>();
    private final List<String> unavailable = new ArrayList<>();
    private long wanted = 1_000_000;

    private final ResponseContentSink sink = new ResponseContentSink() {
        @Override
        public long wants(ObservedResponse response) {
            return response.mimeType().equals("application/json") || response.mimeType().equals("text/html")
                    || response.mimeType().equals("video/mp4") ? wanted : 0;
        }

        @Override
        public void deliver(ObservedResponse response, byte[] body, String reason) {
            if (body != null) delivered.add(response.url() + " " + new String(body, StandardCharsets.UTF_8));
            else unavailable.add(response.url() + " " + reason);
        }
    };

    private NetworkRecorder recorder() {
        // captureBodies=false: ARGOS_NETWORK_BODIES is off, the history capture works anyway.
        NetworkRecorder recorder = new NetworkRecorder(new SecretMasker(), false, () -> Instant.EPOCH, System::nanoTime);
        recorder.contentSink(sink);
        return recorder;
    }

    @Test void wantedBodiesAreReadAfterTheEventsAndIndependentOfNetworkBodies() {
        NetworkRecorder recorder = recorder();
        Response json = finished(recorder, "https://api.example/items?token=secret", "fetch", "application/json",
                "{\"a\":1}", Map.of());
        Response css = finished(recorder, "https://api.example/site.css", "stylesheet", "text/css", "body{}", Map.of());
        verify(json, never()).body();
        recorder.captureQueuedBodies();
        verify(json, times(1)).body();
        verify(css, never()).body();
        assertEquals(1, delivered.size());
        assertFalse(delivered.get(0).contains("secret"), "the URL is masked: " + delivered.get(0));
        assertTrue(delivered.get(0).endsWith("{\"a\":1}"));
    }

    @Test void announcedOversizedFailedAndEmptyStatusResponsesAreNeverRead() {
        NetworkRecorder recorder = recorder();
        wanted = 10;
        Response big = finished(recorder, "https://x.example/big", "fetch", "application/json", "{\"a\":123456789}",
                Map.of("content-length", "5000"));
        Response noContent = response(request("https://x.example/204", "fetch"), 204, "application/json", "", Map.of());
        recorder.requestStarted(noContent.request());
        recorder.responseReceived(noContent);
        recorder.requestFinished(noContent.request());
        Request aborted = request("https://x.example/aborted", "fetch");
        Response abortedResponse = response(aborted, 200, "application/json", "{}", Map.of());
        recorder.requestStarted(aborted);
        recorder.responseReceived(abortedResponse);
        when(aborted.failure()).thenReturn("NS_BINDING_ABORTED");
        recorder.requestFailed(aborted);
        recorder.captureQueuedBodies();
        verify(big, never()).body();
        verify(noContent, never()).body();
        verify(abortedResponse, never()).body();
        assertTrue(delivered.isEmpty());
        assertEquals(1, unavailable.size());
        assertTrue(unavailable.get(0).contains("too large"), unavailable.toString());
    }

    @Test void aBodyLargerThanAllowedAfterReadingIsDroppedAndAGoneBodyNeverFails() {
        NetworkRecorder recorder = recorder();
        wanted = 4;
        finished(recorder, "https://x.example/chunked", "fetch", "application/json", "{\"long\":true}", Map.of());
        Response gone = finished(recorder, "https://x.example/gone", "document", "text/html", "<p>x</p>", Map.of());
        when(gone.body()).thenThrow(new RuntimeException("evicted"));
        assertDoesNotThrow(recorder::captureQueuedBodies);
        assertTrue(delivered.isEmpty());
        assertEquals(2, unavailable.size(), unavailable.toString());
    }

    @Test void theQueueIsBoundedAndTheBudgetLeavesTheRestForTheNextTask() {
        NetworkRecorder recorder = recorder();
        List<Response> responses = new ArrayList<>();
        for (int i = 0; i < NetworkRecorder.MAX_PENDING_CONTENT + 10; i++) {
            responses.add(finished(recorder, "https://x.example/" + i, "xhr", "application/json", "{}", Map.of()));
        }
        assertEquals(10, recorder.droppedContent(), "a full queue drops new responses instead of buffering them");
        recorder.captureQueuedBodies();
        assertEquals(NetworkRecorder.CONTENT_FETCHES_PER_TASK, delivered.size(), "per-task fetch budget");
        for (int i = 0; i < 10; i++) recorder.captureQueuedBodies();
        assertEquals(NetworkRecorder.MAX_PENDING_CONTENT, delivered.size());
        verify(responses.get(responses.size() - 1), never()).body();
    }

    @Test void partialMediaKeepsItsRangeMetadata() {
        NetworkRecorder recorder = recorder();
        List<ResponseContentSink.ObservedResponse> seen = new ArrayList<>();
        recorder.contentSink(new ResponseContentSink() {
            @Override public long wants(ObservedResponse response) { return 100; }
            @Override public void deliver(ObservedResponse response, byte[] body, String reason) { seen.add(response); }
        });
        Request request = request("https://media.example/v.mp4", "media");
        Response response = response(request, 206, "video/mp4", "abcd",
                Map.of("content-range", "bytes 0-3/1000", "content-length", "4"));
        recorder.requestStarted(request);
        recorder.responseReceived(response);
        recorder.requestFinished(request);
        recorder.captureQueuedBodies();
        assertEquals(1, seen.size());
        assertEquals(206, seen.get(0).status());
        assertEquals("bytes 0-3/1000", seen.get(0).contentRange());
        assertEquals(4L, seen.get(0).contentLength());
    }

    private static Response finished(NetworkRecorder recorder, String url, String type, String mime, String body,
                                     Map<String, String> headers) {
        Request request = request(url, type);
        Response response = response(request, 200, mime, body, headers);
        recorder.requestStarted(request);
        recorder.responseReceived(response);
        recorder.requestFinished(request);
        return response;
    }

    private static Request request(String url, String type) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(url);
        when(request.resourceType()).thenReturn(type);
        when(request.headers()).thenReturn(Map.of());
        return request;
    }

    private static Response response(Request request, int status, String contentType, String body,
                                     Map<String, String> extra) {
        Response response = mock(Response.class);
        Map<String, String> headers = new HashMap<>(extra);
        headers.put("content-type", contentType);
        when(response.request()).thenReturn(request);
        when(response.status()).thenReturn(status);
        when(response.statusText()).thenReturn("");
        when(response.headers()).thenReturn(headers);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }
}
