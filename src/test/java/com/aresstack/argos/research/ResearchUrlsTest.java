package com.aresstack.argos.research;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResearchUrlsTest {
    @Test
    void canonicalFormIgnoresSchemeWwwTrailingSlashFragmentAndTracking() {
        String canonical = ResearchUrls.canonical("https://a.example/page?id=7");
        assertEquals(canonical, ResearchUrls.canonical("http://WWW.a.example/page/?utm_source=x&id=7#top"));
        assertNotEquals(canonical, ResearchUrls.canonical("https://a.example/page?id=8"));
        assertEquals("a.example:8080/x", ResearchUrls.canonical("http://a.example:8080/x"));
    }

    @Test
    void familiesAndDocuments() {
        assertEquals("example.co.uk", ResearchUrls.family("https://news.example.co.uk/a"));
        assertEquals("bing.com", ResearchUrls.family("www.bing.com"));
        assertEquals("127.0.0.1", ResearchUrls.family("http://127.0.0.1:9000/a"));
        assertTrue(ResearchUrls.isDocument("https://a.example/paper.PDF"));
        assertFalse(ResearchUrls.isDocument("https://a.example/pdf/viewer"));
        assertFalse(ResearchUrls.isHttp("javascript:void(0)"));
    }
}
