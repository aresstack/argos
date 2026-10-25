package com.aresstack.argos.fetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BackgroundTabTest {
    @Test
    void readsFilenameFromContentDisposition() {
        assertEquals("report.pdf", BackgroundTab.filenameFromDisposition("attachment; filename=\"report.pdf\""));
        assertEquals("Größe.pdf", BackgroundTab.filenameFromDisposition(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''Gr%C3%B6%C3%9Fe.pdf"));
        assertEquals("", BackgroundTab.filenameFromDisposition("inline"));
        assertEquals("", BackgroundTab.filenameFromDisposition(null));
    }

    @Test
    void shortensPlaywrightNavigationErrorsWithoutTheUrl() {
        String raw = "Error {\n  message='net::ERR_CONNECTION_REFUSED at http://127.0.0.1:1/?token=x\n  name='Error";
        assertEquals("Navigation failed: net::ERR_CONNECTION_REFUSED",
                BackgroundTab.navigationError(new com.microsoft.playwright.PlaywrightException(raw)));
        assertEquals("Navigation failed: boom", BackgroundTab.navigationError(
                new com.microsoft.playwright.PlaywrightException("Error {\n  message='boom at http://x/?t=1\n")));
    }

    @Test
    void classifiesHttpStatus() {
        assertNull(FetchFailure.forHttpStatus(200));
        assertNull(FetchFailure.forHttpStatus(304));
        assertEquals(FetchFailure.Kind.NOT_FOUND, FetchFailure.forHttpStatus(404).kind());
        assertEquals(FetchFailure.Kind.BLOCKED, FetchFailure.forHttpStatus(403).kind());
        assertEquals(FetchFailure.Kind.BLOCKED, FetchFailure.forHttpStatus(429).kind());
        assertEquals(FetchFailure.Kind.HTTP_ERROR, FetchFailure.forHttpStatus(500).kind());
    }

    @Test
    void resolvesRelativeAddressesAndRedactsMessages() {
        assertEquals("https://example.org/docs/a.pdf",
                WebAddress.requireHttp("a.pdf", "https://example.org/docs/index.html"));
        FetchFailure invalid = org.junit.jupiter.api.Assertions.assertThrows(FetchFailure.class,
                () -> WebAddress.requireHttp("ftp://user:secret@example.org/x?token=1", null));
        assertEquals(FetchFailure.Kind.INVALID_REQUEST, invalid.kind());
        assertEquals(false, invalid.getMessage().contains("secret"));
        assertEquals(false, invalid.getMessage().contains("token"));
    }
}
