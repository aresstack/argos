package com.aresstack.argos.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchRedirectsTest {
    @Test
    void unwrapsTheThreeEngineWrappers() {
        assertEquals("https://example.org/a b", SearchRedirects.resolve(
                "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa%20b&rut=1").targetUrl());
        assertEquals("https://example.org/doc", SearchRedirects.resolve(
                "https://www.google.com/url?q=https://example.org/doc&sa=U").targetUrl());
        assertEquals("https://w3.org/TR/webdriver-bidi/", SearchRedirects.resolve(
                "https://www.bing.com/ck/a?!&&p=1&u=a1aHR0cHM6Ly93My5vcmcvVFIvd2ViZHJpdmVyLWJpZGkv&ntb=1").targetUrl());
    }

    @Test
    void brokenWrappersAreUnresolvedAndPlainLinksPassThrough() {
        assertEquals(SearchRedirects.Status.UNRESOLVED,
                SearchRedirects.resolve("https://www.bing.com/ck/a?u=zz").status());
        assertEquals(SearchRedirects.Status.UNRESOLVED,
                SearchRedirects.resolve("https://duckduckgo.com/l/?uddg=javascript%3Aalert(1)").status());
        var plain = SearchRedirects.resolve("https://example.org/x");
        assertEquals(SearchRedirects.Status.NOT_A_REDIRECT, plain.status());
        assertEquals("https://example.org/x", plain.targetUrl());
    }

    @Test
    void classifiesTargetsAgainstThePage() {
        assertEquals(DomainNames.DomainRelation.EXTERNAL,
                DomainNames.classify("https://camoufox.com/", "https://html.duckduckgo.com/html/"));
        assertEquals(DomainNames.DomainRelation.SAME_REGISTRABLE_DOMAIN,
                DomainNames.classify("https://duckduckgo.com/about", "https://html.duckduckgo.com/html/"));
        assertEquals("bbc.co.uk", DomainNames.registrableDomain("www.news.bbc.co.uk"));
    }
}
