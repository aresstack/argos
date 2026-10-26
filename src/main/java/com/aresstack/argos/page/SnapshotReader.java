package com.aresstack.argos.page;

import java.util.Optional;

/** Chooses the text extraction strategy by document type and enforces the output limit. */
public final class SnapshotReader {
    public static final int MIN_CHARACTERS = 1_000;
    public static final int MAX_CHARACTERS = 60_000;
    public static final String TRUNCATION_MARKER = "\n[TRUNCATED]";

    public PageSnapshot read(PageSource page, int maxCharacters) {
        int limit = Math.min(MAX_CHARACTERS, Math.max(MIN_CHARACTERS, maxCharacters));
        String contentType = page.contentType();
        DocumentType type = DocumentType.classify(contentType, page.url());
        if (type == DocumentType.UNKNOWN) type = page.hasBody() ? DocumentType.HTML : DocumentType.XML;
        if (type == DocumentType.XML && DocumentText.feed(sourceOf(page).orElse("")).isPresent()) {
            type = DocumentType.FEED;
        }
        if (type == DocumentType.BINARY) {
            return new PageSnapshot(page.url(), page.title(), page.status(), type, contentType, "",
                    false, 0, "none", "Binary document (" + describe(contentType)
                            + "). Its text is not part of the rendered page; use document processing "
                            + "(download and extract) to read it.");
        }
        Extracted extracted = extract(type, page);
        String text = extracted.text();
        boolean truncated = text.length() > limit;
        String visible = truncated ? text.substring(0, limit) + TRUNCATION_MARKER : text;
        String hint = text.isBlank() ? "The document has no readable text yet. If the page is still "
                + "loading, use browser_wait and read again." : null;
        return new PageSnapshot(page.url(), page.title(), page.status(), type, contentType, visible,
                truncated, text.length(), extracted.source(), hint);
    }

    private Extracted extract(DocumentType type, PageSource page) {
        return switch (type) {
            case HTML -> page.hasBody() ? new Extracted(page.bodyText(), "dom")
                    : new Extracted(page.rootText().trim(), "dom");
            case XML, FEED -> sourceOf(page).map(xml -> new Extracted(DocumentText.xml(xml), sourceName(page)))
                    .orElseGet(() -> new Extracted(page.rootText().trim(), "dom"));
            case JSON -> page.responseText().map(json -> new Extracted(DocumentText.json(json), "response"))
                    .orElseGet(() -> new Extracted(DocumentText.json(renderedText(page)), "dom"));
            case TEXT -> page.responseText().map(text -> new Extracted(DocumentText.plainText(text), "response"))
                    .orElseGet(() -> new Extracted(renderedText(page), "dom"));
            default -> new Extracted(renderedText(page), "dom");
        };
    }

    /** XML source: the delivered bytes when available, else the browser's serialization. */
    private static Optional<String> sourceOf(PageSource page) {
        Optional<String> delivered = page.responseText();
        if (delivered.isPresent()) return delivered;
        if (page.hasBody()) return Optional.empty(); // rendered as HTML, not an XML tree
        String serialized = page.documentSource();
        return serialized == null || serialized.isBlank() ? Optional.empty() : Optional.of(serialized);
    }

    private static String sourceName(PageSource page) {
        return page.responseText().isPresent() ? "response" : "dom";
    }

    /** Browsers render plain text and raw JSON inside a synthetic HTML body. */
    private static String renderedText(PageSource page) {
        return page.hasBody() ? page.bodyText() : page.rootText();
    }

    private static String describe(String contentType) {
        String mime = DocumentType.mimeType(contentType);
        return mime.isEmpty() ? "unknown content type" : mime;
    }

    private record Extracted(String text, String source) {
        Extracted {
            text = text == null ? "" : text;
        }
    }
}
