package com.aresstack.argos.network;

/**
 * Receives response bodies the browser already received, for a durable consumer such as the chat's Search History.
 * {@link NetworkRecorder} knows nothing about that consumer: it reports each finished response's metadata, queues the
 * ones the sink {@link #wants}, reads their bodies over the protocol ({@code Response.body()}, which reads the
 * browser's copy and makes no new HTTP request) outside the event handlers, and hands the bytes over.
 * <p>
 * Both methods run on the browser driver thread and must return quickly; a failure is swallowed by the recorder.
 */
public interface ResponseContentSink {
    /**
     * The decision for one finished response, made from its metadata only (inside the protocol event handler, so
     * without any protocol call).
     *
     * @return the largest body in bytes worth reading, or a value {@code <= 0} for "not wanted"
     */
    long wants(ObservedResponse response);

    /**
     * The body of a wanted response; {@code body} is null when it could not be read ({@code unavailable} says why,
     * without URL or content).
     */
    void deliver(ObservedResponse response, byte[] body, String unavailable);

    /**
     * The metadata of one finished response, already masked (URLs) and without request or response headers apart
     * from the content metadata.
     *
     * @param contentLength announced {@code Content-Length}, null when unknown or malformed
     * @param contentRange  {@code Content-Range} of a partial response, or null
     * @param encoded       true when a {@code Content-Encoding} was announced (the length is then the wire size)
     */
    record ObservedResponse(String url, String pageUrl, String method, int status, String contentType,
                            String mimeType, String resourceType, Long contentLength, String contentRange,
                            boolean encoded) {
    }
}
