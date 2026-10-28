package com.aresstack.argos.network;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;

import java.time.Instant;
import java.util.Map;

/**
 * One observed request/response exchange. Values are masked when recorded;
 * the Playwright handles are kept only so a body can be fetched on demand.
 */
final class NetworkEntry {
    enum State { REQUEST, RESPONSE, FINISHED, FAILED }

    final String id;
    final Instant startedAt;
    final long startedNanos;
    final String method;
    final String listUrl;
    final String url;
    final String resourceType;
    final Map<String, String> requestHeaders;
    final String postData;
    final String redirectedFromId;
    final Request request;
    final Page ownerPage;

    State state = State.REQUEST;
    Response response;
    int status;
    String statusText = "";
    String mimeType = "";
    Map<String, String> responseHeaders = Map.of();
    Map<String, String> fullRequestHeaders;
    boolean fullHeadersLoaded;
    Long durationMillis;
    Map<String, Double> timing;
    String failure;
    String body;
    Integer bodyBytes;
    boolean bodyTruncated;
    String bodyUnavailable;
    /** Handed to the content sink once (finish and response events may come in either order). */
    boolean contentOffered;

    NetworkEntry(String id, Instant startedAt, long startedNanos, String method, String listUrl, String url,
                 String resourceType, Map<String, String> requestHeaders, String postData,
                 String redirectedFromId, Request request) {
        this(id, startedAt, startedNanos, method, listUrl, url, resourceType, requestHeaders, postData,
                redirectedFromId, request, null);
    }

    NetworkEntry(String id, Instant startedAt, long startedNanos, String method, String listUrl, String url,
                 String resourceType, Map<String, String> requestHeaders, String postData,
                 String redirectedFromId, Request request, Page ownerPage) {
        this.id = id;
        this.startedAt = startedAt;
        this.startedNanos = startedNanos;
        this.method = method;
        this.listUrl = listUrl;
        this.url = url;
        this.resourceType = resourceType;
        this.requestHeaders = requestHeaders;
        this.postData = postData;
        this.redirectedFromId = redirectedFromId;
        this.request = request;
        this.ownerPage = ownerPage;
    }

    String kind() {
        return switch (state) {
            case REQUEST -> "request";
            case RESPONSE, FINISHED -> "response";
            case FAILED -> "failed";
        };
    }
}
