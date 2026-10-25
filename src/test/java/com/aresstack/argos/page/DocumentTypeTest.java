package com.aresstack.argos.page;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTypeTest {
    @Test
    void classifiesByContentTypeHeader() {
        assertEquals(DocumentType.HTML, DocumentType.classify("text/html; charset=utf-8", "https://a.org/x.json"));
        assertEquals(DocumentType.HTML, DocumentType.classify("application/xhtml+xml", null));
        assertEquals(DocumentType.FEED, DocumentType.classify("application/rss+xml", null));
        assertEquals(DocumentType.FEED, DocumentType.classify("application/atom+xml;charset=UTF-8", null));
        assertEquals(DocumentType.XML, DocumentType.classify("text/xml", null));
        assertEquals(DocumentType.XML, DocumentType.classify("application/xml", null));
        assertEquals(DocumentType.JSON, DocumentType.classify("application/json", null));
        assertEquals(DocumentType.JSON, DocumentType.classify("application/ld+json", null));
        assertEquals(DocumentType.TEXT, DocumentType.classify("text/plain", null));
        assertEquals(DocumentType.TEXT, DocumentType.classify("text/csv", null));
        assertEquals(DocumentType.BINARY, DocumentType.classify("application/pdf", null));
        assertEquals(DocumentType.BINARY, DocumentType.classify("image/png", null));
    }

    @Test
    void fallsBackToUrlPathForMissingOrGenericHeaders() {
        assertEquals(DocumentType.BINARY, DocumentType.classify(null, "https://a.org/paper.PDF?download=1"));
        assertEquals(DocumentType.FEED, DocumentType.classify("application/octet-stream", "https://a.org/feed.rss"));
        assertEquals(DocumentType.BINARY, DocumentType.classify("application/octet-stream", "https://a.org/blob"));
        assertEquals(DocumentType.UNKNOWN, DocumentType.classify(null, "https://a.org/articles/view"));
        assertEquals(DocumentType.UNKNOWN, DocumentType.classify(null, null));
    }

    @Test
    void sourceDocumentsAreTheNonHtmlTextFormats() {
        assertTrue(DocumentType.FEED.isSourceDocument());
        assertTrue(DocumentType.TEXT.isSourceDocument());
        assertFalse(DocumentType.HTML.isSourceDocument());
        assertFalse(DocumentType.BINARY.isSourceDocument());
        assertEquals("feed", DocumentType.FEED.wireName());
    }
}
