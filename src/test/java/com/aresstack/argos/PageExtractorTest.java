package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageExtractorTest {
    private final AriaTree tree = AriaTree.parse(AriaTreeTest.SNAPSHOT);

    @Test void extractsHeadingsWithLevels() {
        assertEquals(List.of(Map.of("level", 1, "text", "Main title")), PageExtractor.headings(tree, 10));
    }

    @Test void resolvesAndDeduplicatesLinks() {
        AriaTree links = AriaTree.parse("""
                - link "A":
                  - /url: /a
                - link "A again":
                  - /url: https://site.test/a
                - link "Mail":
                  - /url: mailto:x@y.z
                - link "Script":
                  - /url: javascript:void(0)
                """);
        assertEquals(List.of(Map.of("text", "A", "url", "https://site.test/a")),
                PageExtractor.links(links, "https://site.test/page", 10));
    }

    @Test void turnsTablesIntoHeadersAndRows() {
        List<Map<String, Object>> tables = PageExtractor.tables(tree);
        assertEquals(1, tables.size());
        assertEquals(List.of("Name", "Wert"), tables.get(0).get("headers"));
        assertEquals(List.of(List.of("a", "1")), tables.get(0).get("rows"));
    }

    @Test void extractsListItems() {
        assertEquals(List.of(Map.of("items", List.of("eins", "zwei"))), PageExtractor.lists(tree));
    }

    @Test void readsScholarlyAndSocialMetadataFromHead() {
        String head = """
                <meta charset="utf-8"><title>x</title>
                <meta name="description" content="Short &amp; sweet">
                <meta property="og:title" content='OG title'>
                <meta name="citation_title" content="A paper">
                <meta name="citation_author" content="Ada"><meta name="citation_author" content="Bob">
                <meta name="viewport" content="width=device-width">
                <link rel="canonical" href="/paper/1">
                """;
        Map<String, Object> metadata = PageExtractor.metadata(head, "https://journal.test/x?y");
        assertEquals("Short & sweet", metadata.get("description"));
        assertEquals("OG title", metadata.get("og:title"));
        assertEquals("A paper", metadata.get("citation_title"));
        assertEquals(List.of("Ada", "Bob"), metadata.get("citation_author"));
        assertEquals("https://journal.test/paper/1", metadata.get("canonical"));
        assertFalse(metadata.containsKey("viewport"));
    }

    @Test void parsesJsonLdAndCollectsTypes() {
        List<Object> blocks = PageExtractor.jsonLd(List.of(
                "{\"@type\":\"ScholarlyArticle\",\"name\":\"X\"}",
                "{\"@graph\":[{\"@type\":\"Person\"},{\"@type\":[\"Organization\",\"Thing\"]}]}",
                "{broken"), 10_000);
        assertEquals(3, blocks.size());
        assertTrue(blocks.get(2) instanceof Map<?, ?> invalid && invalid.containsKey("invalid"));
        assertEquals(List.of("ScholarlyArticle", "Person", "Organization", "Thing"), PageExtractor.jsonLdTypes(blocks));
    }
}
