package com.aresstack.argos.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinkExtractorTest {
    private final LinkExtractor extractor = new LinkExtractor();

    @Test void resolvesRelativeLinksAndDropsNonWebTargets() {
        List<LinkExtractor.Anchor> anchors = extractor.extract("""
                <a href="/papers/1">First   paper</a>
                <a href="mailto:a@b.c">Mail</a>
                <a href="javascript:void(0)">Script</a>
                <a href="#top">Top</a>
                <a href="https://other.example/x"><img alt="Logo"></a>
                <a href="/papers/1"></a>
                """, "https://site.example/list?q=1");
        assertEquals(List.of(
                new LinkExtractor.Anchor("First paper", "https://site.example/papers/1"),
                new LinkExtractor.Anchor("Logo", "https://other.example/x")), anchors);
    }

    @Test void prefersVisibleTextOverEmptyDuplicates() {
        List<LinkExtractor.Anchor> anchors = extractor.extract(
                "<a href='/a'></a><a href='/a' aria-label='Read more'>x</a>", "https://s.example/");
        assertEquals("x", anchors.get(0).text());
        assertEquals(1, anchors.size());
    }

    @Test void toleratesDocumentsWithoutHtml() {
        assertTrue(extractor.extract("", "https://s.example/").isEmpty());
        assertTrue(extractor.extract("{\"a\":1}", "https://s.example/").isEmpty());
    }
}
