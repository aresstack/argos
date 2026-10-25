package com.aresstack.argos.page;

import com.aresstack.argos.FixtureServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Document-type dependent snapshots against real HTTP responses from a local fixture
 * server. The browser side is replaced by {@link FakePage}, which fails exactly where
 * the old implementation failed: reading an HTML body that does not exist.
 */
class SnapshotReaderTest {
    private static FixtureServer server;
    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final SnapshotReader reader = new SnapshotReader();

    @BeforeAll
    static void start() throws Exception {
        server = new FixtureServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** What Firefox shows for a document, plus whether the protocol kept the response body. */
    private record FakePage(String url, String title, int status, String contentType, String delivered,
                            boolean protocolKeepsBody, String renderedBody, String rootText,
                            String documentSource) implements PageSource {
        @Override public Optional<String> responseText() {
            return protocolKeepsBody ? Optional.of(delivered) : Optional.empty();
        }
        @Override public boolean hasBody() { return renderedBody != null; }
        @Override public String bodyText() {
            if (renderedBody == null) throw new IllegalStateException("locator('body') timed out");
            return renderedBody;
        }
    }

    private static HttpResponse<String> fetch(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(server.url(path))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Firefox renders XML as an XML tree: no HTML body, serialization equals the source. */
    private static FakePage xmlPage(String path, boolean protocolKeepsBody) throws Exception {
        HttpResponse<String> response = fetch(path);
        return new FakePage(server.url(path), "", response.statusCode(),
                response.headers().firstValue("content-type").orElse(null), response.body(),
                protocolKeepsBody, null, response.body().replaceAll("<[^>]+>", " "), response.body());
    }

    @Test
    void readsRssFeedWithoutHtmlBody() throws Exception {
        PageSnapshot snapshot = reader.read(xmlPage("/springer-feed.rss", true), 16_000);
        assertEquals(DocumentType.FEED, snapshot.documentType());
        assertEquals("response", snapshot.source());
        assertEquals(200, snapshot.status());
        assertTrue(snapshot.text().contains("1. Robust page readiness for research agents"), snapshot.text());
        assertNull(snapshot.hint());
    }

    @Test
    void readsFeedFromRenderedXmlWhenProtocolDropsBody() throws Exception {
        PageSnapshot snapshot = reader.read(xmlPage("/springer-feed.rss", false), 16_000);
        assertEquals(DocumentType.FEED, snapshot.documentType());
        assertEquals("dom", snapshot.source());
        assertTrue(snapshot.text().contains("2. Feeds are documents, too"), snapshot.text());
    }

    @Test
    void detectsFeedServedAsGenericXml() throws Exception {
        FakePage generic = new FakePage(server.url("/feed"), "", 200, "text/xml",
                new String(FixtureServer.fixture("springer-feed.rss")), true, null, "", "");
        assertEquals(DocumentType.FEED, reader.read(generic, 16_000).documentType());
    }

    @Test
    void returnsPlainXmlSource() throws Exception {
        PageSnapshot snapshot = reader.read(xmlPage("/sitemap.xml", true), 16_000);
        assertEquals(DocumentType.XML, snapshot.documentType());
        assertTrue(snapshot.text().contains("<loc>https://example.org/papers/1</loc>"), snapshot.text());
    }

    @Test
    void readsXmlEvenWithoutContentTypeOrBody() {
        // Old failure: unknown document without <body> → locator("body").innerText() timed out.
        FakePage unknown = new FakePage("https://example.org/export", "", 0, null, null, false,
                null, "Item text", "<export><item>Item text</item></export>");
        PageSnapshot snapshot = reader.read(unknown, 16_000);
        assertEquals(DocumentType.XML, snapshot.documentType());
        assertEquals("<export><item>Item text</item></export>", snapshot.text());
    }

    @Test
    void prettyPrintsJsonFromResponseAndFromRenderedViewer() throws Exception {
        HttpResponse<String> response = fetch("/data.json");
        String type = response.headers().firstValue("content-type").orElseThrow();
        PageSnapshot delivered = reader.read(new FakePage(server.url("/data.json"), "", 200, type,
                response.body(), true, "viewer chrome", "", ""), 16_000);
        assertEquals(DocumentType.JSON, delivered.documentType());
        assertTrue(delivered.text().contains("\"name\" : \"second\""), delivered.text());
        PageSnapshot rendered = reader.read(new FakePage(server.url("/data.json"), "", 200, type,
                response.body(), false, response.body(), "", ""), 16_000);
        assertEquals("dom", rendered.source());
        assertTrue(rendered.text().contains("\"name\" : \"second\""), rendered.text());
    }

    @Test
    void returnsPlainTextUnchanged() throws Exception {
        HttpResponse<String> response = fetch("/notes.txt");
        PageSnapshot snapshot = reader.read(new FakePage(server.url("/notes.txt"), "", 200,
                response.headers().firstValue("content-type").orElseThrow(), response.body(), true,
                "Plain text line one indented line two", "", ""), 16_000);
        assertEquals(DocumentType.TEXT, snapshot.documentType());
        assertEquals("Plain text line one\n  indented line two\n", snapshot.text());
    }

    @Test
    void reportsBinaryDocumentsWithoutReadingThem() throws Exception {
        HttpResponse<String> response = fetch("/paper.pdf");
        PageSource pdf = new FakePage(server.url("/paper.pdf"), "paper.pdf", 200,
                response.headers().firstValue("content-type").orElseThrow(), null, false, null, null, null);
        PageSnapshot snapshot = reader.read(pdf, 16_000);
        assertEquals(DocumentType.BINARY, snapshot.documentType());
        assertEquals("", snapshot.text());
        assertEquals("none", snapshot.source());
        assertNotNull(snapshot.hint());
        assertTrue(snapshot.hint().contains("application/pdf"), snapshot.hint());
    }

    @Test
    void readsVisibleHtmlText() throws Exception {
        HttpResponse<String> response = fetch("/page.html");
        PageSnapshot snapshot = reader.read(new FakePage(server.url("/page.html"), "Fixture page", 200,
                response.headers().firstValue("content-type").orElseThrow(), response.body(), true,
                "Static fixture\nReadable paragraph.", "", ""), 16_000);
        assertEquals(DocumentType.HTML, snapshot.documentType());
        assertEquals("dom", snapshot.source());
        assertEquals("Static fixture\nReadable paragraph.", snapshot.text());
        assertEquals("Fixture page", snapshot.title());
    }

    @Test
    void enforcesExistingOutputLimits() {
        String longText = "x".repeat(70_000);
        FakePage page = new FakePage("https://example.org/", "", 200, "text/html", null, false,
                longText, "", "");
        PageSnapshot capped = reader.read(page, 999_999);
        assertTrue(capped.truncated());
        assertEquals(70_000, capped.characters());
        assertEquals(SnapshotReader.MAX_CHARACTERS + SnapshotReader.TRUNCATION_MARKER.length(), capped.text().length());
        PageSnapshot minimum = reader.read(page, 10);
        assertEquals(SnapshotReader.MIN_CHARACTERS + SnapshotReader.TRUNCATION_MARKER.length(), minimum.text().length());
    }

    @Test
    void hintsWhenPageHasNoTextYet() {
        PageSnapshot empty = reader.read(new FakePage("https://example.org/app", "", 200, "text/html",
                null, false, "", "", ""), 16_000);
        assertFalse(empty.truncated());
        assertTrue(empty.hint().contains("browser_wait"), empty.hint());
    }
}
