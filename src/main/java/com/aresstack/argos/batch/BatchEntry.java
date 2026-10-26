package com.aresstack.argos.batch;

import com.aresstack.argos.fetch.FetchedResource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome for one requested address of a batch. {@code document} holds raw bytes of a non-HTML resource
 * until its text has been extracted (outside the browser thread); it is never part of the tool result.
 */
public record BatchEntry(String requestedUrl, Status status, String finalUrl, int httpStatus, String contentType,
                         String title, String text, boolean truncated, boolean loadComplete, String reason,
                         long elapsedMillis, FetchedResource document) {

    public enum Status {
        SUCCESS, DUPLICATE, INVALID_REQUEST, NOT_FOUND, BLOCKED, HTTP_ERROR, TIMEOUT, TOO_LARGE,
        EXTRACTION_FAILED, FAILED
    }

    static BatchEntry skipped(String url, Status status, String reason) {
        return new BatchEntry(url, status, "", 0, "", "", "", false, false, reason, 0, null);
    }

    BatchEntry withText(String newText, boolean newTruncated, Status newStatus, String newReason) {
        return new BatchEntry(requestedUrl, newStatus, finalUrl, httpStatus, contentType, title, newText,
                newTruncated, loadComplete, newReason, elapsedMillis, null);
    }

    /** Compact map for the MCP result; empty fields are left out. */
    public Map<String, Object> toResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", requestedUrl);
        result.put("status", status.name());
        if (!finalUrl.isEmpty() && !finalUrl.equals(requestedUrl)) result.put("finalUrl", finalUrl);
        if (httpStatus > 0) result.put("httpStatus", httpStatus);
        if (!contentType.isEmpty()) result.put("contentType", contentType);
        if (!title.isEmpty()) result.put("title", title);
        if (!reason.isEmpty()) result.put("reason", reason);
        if (status == Status.SUCCESS) {
            result.put("text", text);
            result.put("truncated", truncated);
            if (!loadComplete) result.put("loadComplete", false);
        }
        result.put("elapsedMs", elapsedMillis);
        return result;
    }
}
