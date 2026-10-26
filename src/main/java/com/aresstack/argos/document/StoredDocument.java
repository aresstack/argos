package com.aresstack.argos.document;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A downloaded document kept in Argos' temporary store. The file path is never shown to the agent. */
public record StoredDocument(String downloadId, String sourceUrl, String finalUrl, String filename,
                             String contentType, DocumentFormat format, long size, Instant downloadedAt,
                             Instant expiresAt, Path file) {

    /** Metadata for tool results: everything except the local file path. */
    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("downloadId", downloadId);
        metadata.put("sourceUrl", sourceUrl);
        if (!finalUrl.equals(sourceUrl)) metadata.put("finalUrl", finalUrl);
        metadata.put("filename", filename);
        metadata.put("contentType", contentType);
        metadata.put("format", format.name());
        metadata.put("size", size);
        metadata.put("expiresAt", expiresAt.toString());
        return metadata;
    }
}
