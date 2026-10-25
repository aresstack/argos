package com.aresstack.argos.document;

/**
 * Readable text of a document. {@code readable} is false when the format is unknown or the content could not
 * be parsed; {@code note} then says why (never with file system paths or credentials).
 */
public record DocumentText(DocumentFormat format, boolean readable, String text, boolean truncated,
                           int pages, String note) {
    public static DocumentText unreadable(DocumentFormat format, String note) {
        return new DocumentText(format, false, "", false, 0, note);
    }
}
