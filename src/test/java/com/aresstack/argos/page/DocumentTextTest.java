package com.aresstack.argos.page;

import com.aresstack.argos.FixtureServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextTest {
    @TempDir Path temporary;

    private static String fixture(String name) {
        return new String(FixtureServer.fixture(name), StandardCharsets.UTF_8);
    }

    @Test
    void rendersRssAsNumberedEntries() {
        String text = DocumentText.xml(fixture("springer-feed.rss"));
        assertTrue(text.startsWith("Feed: Springer: Latest articles"), text);
        assertTrue(text.contains("Recently published research articles"), text);
        assertTrue(text.contains("2 entries"), text);
        assertTrue(text.contains("1. Robust page readiness for research agents"), text);
        assertTrue(text.contains("   https://link.springer.com/article/10.1000/argos-1"), text);
        assertTrue(text.contains("We study dynamic content loading."), text);
        assertTrue(text.contains("2. Feeds are documents, too"), text);
        assertTrue(text.contains("2026-10-24"), text);
        assertFalse(text.contains("<p>"), text);
    }

    @Test
    void rendersAtomPreferringAlternateLink() {
        String text = DocumentText.xml(fixture("atom.xml"));
        assertTrue(text.contains("Feed: Example Atom"), text);
        assertTrue(text.contains("Preprints"), text);
        assertTrue(text.contains("1. Atom entry one\n   https://example.org/one\n   2026-10-20T10:00:00Z\n   Short summary"), text);
    }

    @Test
    void rendersRdfFeeds() {
        String rdf = """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/">
                  <channel><title>RDF channel</title></channel>
                  <item><title>RDF item</title><link>https://example.org/rdf</link></item>
                </rdf:RDF>""";
        String text = DocumentText.xml(rdf);
        assertTrue(text.contains("Feed: RDF channel"), text);
        assertTrue(text.contains("1. RDF item\n   https://example.org/rdf"), text);
    }

    @Test
    void returnsOtherXmlAsSource() {
        String sitemap = fixture("sitemap.xml");
        assertEquals(sitemap.trim(), DocumentText.xml(sitemap));
        assertEquals("not <xml", DocumentText.xml("not <xml"));
    }

    @Test
    void neverResolvesExternalEntities() throws Exception {
        Path secret = temporary.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");
        String hostile = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<rss><channel><title>&x;</title></channel></rss>";
        assertFalse(DocumentText.xml(hostile).contains("TOP-SECRET"));
    }

    @Test
    void prettyPrintsJsonAndKeepsInvalidJson() {
        String pretty = DocumentText.json(fixture("data.json"));
        assertTrue(pretty.contains("\"items\" : ["), pretty);
        assertTrue(pretty.contains("\n"), pretty);
        assertEquals("{broken", DocumentText.json("{broken"));
    }

    @Test
    void reducesFeedMarkupToPlainText() {
        assertEquals("a & b c", DocumentText.plain("<b>a</b> &amp; b\n\n c"));
    }
}
