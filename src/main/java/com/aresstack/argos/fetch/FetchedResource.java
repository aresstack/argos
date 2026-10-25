package com.aresstack.argos.fetch;

/**
 * Bytes the browser itself loaded for one address, with what the server declared about them.
 * {@code status} is 0 when the browser delivered the content as a download (no navigation response).
 */
public record FetchedResource(String requestedUrl, String finalUrl, int status, String contentType,
                              String suggestedFilename, byte[] content) {
    public FetchedResource {
        content = content == null ? new byte[0] : content;
        contentType = contentType == null ? "" : contentType;
        suggestedFilename = suggestedFilename == null ? "" : suggestedFilename;
    }

    public long size() {
        return content.length;
    }
}
