package com.aresstack.argos.history;

import java.time.Instant;

/**
 * One distinct stored response body ({@code resources/<resourceId>.json} plus {@code .body}). The id is derived
 * from the stored bytes, so identical bodies (also under different URLs) are kept once; a changed body of the same
 * URL gets its own id. Text bodies are stored masked as UTF-8, binary bodies as the received bytes.
 *
 * @param textKind {@link ResourceCategory.TextKind} name: html, text, code or binary
 * @param masked   true when secrets were removed before storing (the body is then no unmodified original)
 */
public record StoredResource(String resourceId, String mimeType, String category, String textKind,
                             String contentHash, long size, Instant storedAt, boolean masked) {
    public boolean textual() {
        return !"binary".equals(textKind);
    }
}
