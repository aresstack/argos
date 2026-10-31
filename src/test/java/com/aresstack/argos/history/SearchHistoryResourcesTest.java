package com.aresstack.argos.history;

import com.aresstack.argos.network.ResponseContentSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.aresstack.argos.history.HistoryFixtures.page;
import static org.junit.jupiter.api.Assertions.*;

/** Captured network responses in the Search History (spec Tests A, B, C, E-J on the storage side). */
class SearchHistoryResourcesTest {
    @TempDir Path root;

    @AfterEach void awaitIndexing() throws Exception {
        SearchHistoryCapture.awaitWriter();
        SearchHistory.awaitIndexing();
    }

    private static final Map<String, String> CONTENT = new LinkedHashMap<>();
    static {
        CONTENT.put("text/html; charset=utf-8", "<html><body><p>htmlterm</p><script>x()</script></body></html>");
        CONTENT.put("application/xhtml+xml", "<html xmlns='http://www.w3.org/1999/xhtml'><body>xhtmlterm</body></html>");
        CONTENT.put("application/json", "{\"title\":\"jsonterm\"}");
        CONTENT.put("application/ld+json", "{\"@type\":\"Article\",\"name\":\"ldjsonterm\"}");
        CONTENT.put("application/xml", "<r>xmlterm</r>");
        CONTENT.put("application/atom+xml", "<feed><title>atomterm</title></feed>");
        CONTENT.put("text/plain; charset=utf-8", "plainterm");
        CONTENT.put("text/markdown", "# markdownterm");
        CONTENT.put("text/csv", "a,b\ncsvterm,1");
    }

    private static final Map<ResourceCategory, String> OPTIONAL = new EnumMap<>(Map.of(
            ResourceCategory.STYLES, "text/css", ResourceCategory.SCRIPTS, "text/javascript",
            ResourceCategory.IMAGES, "image/svg+xml", ResourceCategory.FONTS, "font/woff2",
            ResourceCategory.AUDIO, "audio/mpeg", ResourceCategory.VIDEO, "video/mp4",
            ResourceCategory.PDF, "application/pdf", ResourceCategory.OTHER, "application/x-unknown"));

    private SearchHistory history(String chat) {
        return history(chat, Map.of());
    }

    private SearchHistory history(String chat, Map<ResourceCategory, Boolean> defaults) {
        return new SearchHistory(root.resolve(chat).resolve("history"), defaults);
    }

    private static SearchHistory.Received received(String url, String type, byte[] body) {
        return new SearchHistory.Received(url, "https://page.example/", 200, type, "fetch", body,
                (long) body.length, null, false);
    }

    private static SearchHistory.Received received(String url, String type, String body) {
        return received(url, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static List<HistoryEvent.NetworkResource> resources(SearchHistory history) throws Exception {
        List<HistoryEvent.NetworkResource> found = new ArrayList<>();
        history.repository().forEach(0, event -> {
            if (event instanceof HistoryEvent.NetworkResource resource) found.add(resource);
        });
        return found;
    }

    @Test void testA_theDefaultAllowlistIsStoredAndSearchable() throws Exception {
        SearchHistory history = history("a");
        int i = 0;
        for (Map.Entry<String, String> entry : CONTENT.entrySet()) {
            assertNotNull(history.recordResource(received("https://api.example/r" + i++, entry.getKey(),
                    entry.getValue()), "start"), entry.getKey());
        }
        for (String term : List.of("htmlterm", "xhtmlterm", "jsonterm", "ldjsonterm", "xmlterm", "atomterm",
                "plainterm", "markdownterm", "csvterm")) {
            List<SearchHistory.Found> found = history.search(term, 5);
            assertEquals(1, found.size(), term);
            assertEquals("resource", found.get(0).type());
            assertEquals("content", found.get(0).category());
            assertTrue(found.get(0).snippet().contains(term), found.get(0).snippet());
        }
        HistoryEvent.NetworkResource html = resources(history).get(0);
        String stored = Files.readString(history.repository().resourceBody(html.resourceId()));
        assertFalse(stored.contains("<script"), "HTML is cleaned with ArchiveSanitizer");
        assertTrue(html.masked(), "a cleaned body is no unmodified original");
        assertEquals("text/html", html.mimeType());
    }

    @Test void testB_optionalCategoriesAreOffByDefaultAndEachSwitchesOnlyItself() throws Exception {
        SearchHistory plain = history("plain");
        for (Map.Entry<ResourceCategory, String> entry : OPTIONAL.entrySet()) {
            assertNull(plain.recordResource(received("https://cdn.example/x", entry.getValue(), "body"), "start"),
                    entry.getKey().key());
        }
        assertNull(plain.recordResource(received("https://cdn.example/none", null, "untyped"), "start"),
                "no Content-Type is OTHER, never text");
        assertTrue(resources(plain).isEmpty());
        assertFalse(Files.exists(plain.repository().root().resolve("resources")));

        for (ResourceCategory enabled : OPTIONAL.keySet()) {
            SearchHistory history = history("only-" + enabled.key(), Map.of(enabled, true));
            for (Map.Entry<ResourceCategory, String> entry : OPTIONAL.entrySet()) {
                HistoryEvent.NetworkResource event = history.recordResource(received("https://cdn.example/"
                        + entry.getKey().key(), entry.getValue(), "body-" + entry.getKey().key()), "start");
                assertEquals(entry.getKey() == enabled, event != null, enabled + " on, offered " + entry.getKey());
            }
            assertEquals(enabled.key(), resources(history).get(0).category());
        }
    }

    @Test void binaryBodiesAreStoredAsReceivedAndNotIndexedAsText() throws Exception {
        SearchHistory history = history("a", Map.of(ResourceCategory.IMAGES, true));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff, 'p', 'n', 'g', 't', 'e', 'r', 'm'};
        HistoryEvent.NetworkResource event = history.recordResource(received("https://cdn.example/logo.png",
                "image/png", png), "start");
        assertArrayEquals(png, Files.readAllBytes(history.repository().resourceBody(event.resourceId())));
        assertFalse(event.masked());
        assertTrue(history.search("pngterm", 5).isEmpty(), "binary content is no full text");
        assertEquals(1, history.search("logo.png", 5).size(), "but findable by URL");
    }

    @Test void testC_dynamicDataIsSearchableBeyondTheVisibleSnapshot() throws Exception {
        SearchHistory history = history("a");
        history.recordPage(page("https://shop.example/", "Shop", "Welcome to the shop"), "start", "web_open", true);
        history.recordResource(new SearchHistory.Received("https://shop.example/api/items", "https://shop.example/",
                200, "application/json", "fetch", "{\"items\":[{\"name\":\"quasarwidget\"}]}".getBytes(), null,
                null, false), "start");
        history.recordResource(new SearchHistory.Received("https://shop.example/api/feed.xml", "https://shop.example/",
                200, "text/xml", "xhr", "<items><i>nebulagadget</i></items>".getBytes(), null, null, false), "start");
        assertTrue(history.search("welcome", 5).stream().noneMatch(f -> f.type().equals("resource")));
        SearchHistory.Found json = history.search("quasarwidget", 5).get(0);
        assertEquals("resource", json.type());
        assertEquals("https://shop.example/api/items", json.url());
        assertEquals("application/json", json.mimeType());
        assertEquals("resource", history.search("nebulagadget", 5).get(0).type());
    }

    @Test void identicalBodiesAreStoredOnceAndChangedVersionsAreKept() throws Exception {
        SearchHistory history = history("a");
        history.recordResource(received("https://api.example/v", "application/json", "{\"v\":\"alphaversion\"}"), "start");
        history.recordResource(received("https://api.example/v", "application/json", "{\"v\":\"alphaversion\"}"), "start");
        history.recordResource(received("https://mirror.example/v", "application/json", "{\"v\":\"alphaversion\"}"), "start");
        history.recordResource(received("https://api.example/v", "application/json", "{\"v\":\"betaversion\"}"), "start");
        List<HistoryEvent.NetworkResource> events = resources(history);
        assertEquals(4, events.size(), "every observation is an event");
        assertEquals(2, events.stream().map(HistoryEvent.NetworkResource::resourceId).distinct().count());
        try (Stream<Path> files = Files.list(history.repository().root().resolve("resources"))) {
            assertEquals(4, files.count(), "two bodies with metadata");
        }
        assertEquals(1, history.search("alphaversion", 10).size(), "identical content is one hit");
        assertEquals(1, history.search("betaversion", 10).size(), "the changed version is kept");
    }

    @Test void identicalBytesKeepEachObservationsMetadataAndOneConsistentFileName() throws Exception {
        SearchHistory history = history("a");
        String body = "same bytes dedupterm";
        HistoryEvent.NetworkResource json = history.recordResource(received("https://a.example/x", "application/json",
                body), "start");
        HistoryEvent.NetworkResource text = history.recordResource(received("https://b.example/x", "text/plain",
                body), "start");
        // A masked original that cleans to the same bytes as an unmasked one.
        HistoryEvent.NetworkResource plain = history.recordResource(received("https://c.example/x", "text/plain",
                "{\"password\":\"[REDACTED]\"}"), "start");
        HistoryEvent.NetworkResource masked = history.recordResource(received("https://d.example/x", "text/plain",
                "{\"password\":\"hunter2\"}"), "start");
        assertEquals(json.resourceId(), text.resourceId(), "bytes are stored once");
        assertEquals("application/json", json.mimeType());
        assertEquals("text/plain", text.mimeType(), "the second observation keeps its own MIME type");
        assertEquals(plain.resourceId(), masked.resourceId());
        assertFalse(plain.masked());
        assertTrue(masked.masked(), "masking belongs to the observation");

        SearchHistoryExporter exporter = new SearchHistoryExporter(history.repository());
        ObjectMapper mapper = new ObjectMapper();
        List<String> refs = new ArrayList<>();
        for (String line : Files.readAllLines(exporter.export(SearchHistoryExporter.Format.JSONL, false, false).file())) {
            JsonNode node = mapper.readTree(line);
            refs.add(node.get("bodyRef").asText());
            if (node.get("url").asText().equals("https://b.example/x")) assertEquals("text/plain", node.get("mimeType").asText());
        }
        assertEquals(refs.get(0), refs.get(1), "one bodyRef per stored body");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(exporter.export(SearchHistoryExporter.Format.ZIP, false, false)
                .file()); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) entries.put(entry.getName(), zip.readAllBytes());
        }
        for (String ref : refs) assertTrue(entries.containsKey(ref), ref + " in " + entries.keySet());
        JsonNode manifest = mapper.readTree(entries.get("resources/manifest.json"));
        assertEquals(4, manifest.size());
        assertEquals(refs.get(0), manifest.get(1).get("file").asText());
        assertEquals("text/plain", manifest.get(1).get("mimeType").asText());
        assertEquals(2, entries.keySet().stream().filter(n -> n.startsWith("resources/") && !n.endsWith("manifest.json")).count());
    }

    @Test void thePagesOwnDocumentIsNoSecondHitNextToItsSnapshot() throws Exception {
        SearchHistory history = history("a");
        history.recordPage(page("https://doc.example/", "Doc", "uniqueword in the page"), "start", "web_open", true);
        history.recordResource(new SearchHistory.Received("https://doc.example/", "https://doc.example/", 200,
                "text/html", "document", "<html><body>uniqueword in the page</body></html>".getBytes(), null, null,
                false), "start");
        List<SearchHistory.Found> found = history.search("uniqueword", 10);
        assertEquals(List.of("page"), found.stream().map(SearchHistory.Found::type).toList());
    }

    @Test void scriptsAndStylesAreRecognizableAndRankBelowPageContent() throws Exception {
        SearchHistory history = history("a", Map.of(ResourceCategory.SCRIPTS, true));
        history.recordResource(received("https://cdn.example/app.js", "application/javascript",
                "var zebra = 'zebra zebra zebra zebra';"), "start");
        history.recordPage(page("https://zoo.example/", "Zoo", "A zebra lives here."), "start", "web_open", true);
        List<SearchHistory.Found> found = history.search("zebra", 10);
        assertEquals("page", found.get(0).type(), "page content first");
        assertEquals("resource", found.get(1).type());
        assertEquals("scripts", found.get(1).category());
    }

    @Test void secretsInTextBodiesAndUrlsAreMasked() throws Exception {
        SearchHistory history = history("a");
        HistoryEvent.NetworkResource event = history.recordResource(received(
                "https://api.example/me?access_token=tok123&page=2", "application/json",
                "{\"user\":\"ann\",\"access_token\":\"abcdef123456\",\"password\":\"hunter2\"}"), "start");
        assertFalse(event.url().contains("tok123"), event.url());
        assertTrue(event.url().contains("page=2"));
        String stored = Files.readString(history.repository().resourceBody(event.resourceId()));
        assertFalse(stored.contains("abcdef123456") || stored.contains("hunter2"), stored);
        assertTrue(event.masked());
    }

    @Test void testE_limitsForUnknownSizesPartialMediaAndTheWriterQueue() throws Exception {
        CaptureSettings media = new CaptureSettings(root.resolve("m"), Map.of(ResourceCategory.VIDEO, true,
                ResourceCategory.IMAGES, true));
        ResponseContentSink.ObservedResponse unknownVideo = observed("video/mp4", null, false);
        assertEquals(0, SearchHistoryCapture.limit(unknownVideo, media), "media of unknown size is never read");
        assertEquals(0, SearchHistoryCapture.limit(observed("video/mp4", 4_000L, true), media),
                "an encoded length is no reliable size");
        assertEquals(0, SearchHistoryCapture.limit(observed("video/mp4", 1L << 40, false), media));
        assertEquals(SearchHistoryCapture.MAX_BINARY_BODY_BYTES,
                SearchHistoryCapture.limit(observed("video/mp4", 4_000L, false), media));
        assertEquals(0, SearchHistoryCapture.limit(observed("application/json", null, false), media),
                "text of unknown size is never read");
        assertEquals(0, SearchHistoryCapture.limit(observed("application/json", 100L, true), media),
                "compressed text is never read");
        assertEquals(0, SearchHistoryCapture.limit(observed("application/json", 9L * 1024 * 1024, false), media));
        assertEquals(SearchHistoryCapture.MAX_TEXT_BODY_BYTES,
                SearchHistoryCapture.limit(observed("application/json", 100L, false), media));
        assertEquals(0, SearchHistoryCapture.limit(observed("text/css", 10L, false), media), "styles are off");
        assertEquals(0, SearchHistoryCapture.limit(new ResponseContentSink.ObservedResponse("data:text/plain,x", null,
                "GET", 200, "text/plain", "text/plain", "fetch", 1L, null, false), media), "only http(s)");

        SearchHistory history = history("a", Map.of(ResourceCategory.VIDEO, true));
        HistoryEvent.NetworkResource partial = history.recordResource(new SearchHistory.Received(
                "https://media.example/v.mp4", null, 206, "video/mp4", "media", new byte[] {1, 2, 3, 4}, 4L,
                "bytes 0-3/1000", false), "start");
        assertFalse(partial.complete(), "a range response is never called complete");
        assertEquals("bytes 0-3/1000", partial.contentRange());
        HistoryEvent.NetworkResource truncated = history.recordResource(new SearchHistory.Received(
                "https://media.example/w.mp4", null, 200, "video/mp4", "media", new byte[] {1, 2}, 10L, null, false),
                "start");
        assertFalse(truncated.complete(), "fewer bytes than announced");
        assertNull(history.recordResource(received("https://x.example/empty", "application/json", new byte[0]), "start"));
    }

    @Test void theSinkStoresInTheCallingChatWithItsContextAndNeverFailsTheBrowser() throws Exception {
        SearchHistory history = history("a");
        SearchHistoryCapture capture = new SearchHistoryCapture(history, null, 1_000);
        capture.useContext("context-2");
        ResponseContentSink sink = capture.networkContent();
        ResponseContentSink.ObservedResponse json = observed("application/json", 15L, false);
        assertTrue(sink.wants(json) > 0);
        sink.deliver(json, "{\"k\":\"sinkterm\"}".getBytes(), null);
        sink.deliver(json, null, "body no longer available");
        SearchHistoryCapture.awaitWriter();
        List<HistoryEvent.NetworkResource> events = resources(history);
        assertEquals(1, events.size());
        assertEquals("context-2", events.get(0).contextId(), "the context is metadata");
        assertEquals("resource", history.search("sinkterm", 3).get(0).type());
    }

    @Test void testF_chatsAreStrictlySeparatedAndContextsShareOneHistory() throws Exception {
        SearchHistory chatA = history("a");
        SearchHistory chatB = history("b");
        chatA.recordResource(received("https://same.example/api", "application/json", "{\"x\":\"alphadata\"}"), "start");
        chatA.recordResource(received("https://same.example/api", "application/json", "{\"x\":\"gammadata\"}"),
                "context-2");
        chatB.recordResource(received("https://same.example/api", "application/json", "{\"x\":\"betadata\"}"), "start");
        chatA.captureSettings().set(Map.of(ResourceCategory.IMAGES, true));
        assertTrue(chatB.search("alphadata", 5).isEmpty());
        assertTrue(chatA.search("betadata", 5).isEmpty());
        assertEquals(1, chatA.search("gammadata", 5).size(), "a second user context writes the same history");
        assertTrue(chatA.captureSettings().enabled(ResourceCategory.IMAGES));
        assertFalse(chatB.captureSettings().enabled(ResourceCategory.IMAGES), "settings are per chat");
        String idA = resources(chatA).get(0).resourceId();
        assertFalse(chatB.repository().hasResource(idA), "a resource id of chat A is nothing in chat B");
        assertThrows(IllegalArgumentException.class, () -> chatA.repository().resourceBody("../../b/history/x"));
        SearchHistoryExporter.Export export = new SearchHistoryExporter(chatB.repository())
                .export(SearchHistoryExporter.Format.JSONL, false, false);
        String jsonl = Files.readString(export.file());
        assertTrue(jsonl.contains("betadata") && !jsonl.contains("alphadata"), jsonl);
    }

    @Test void testG_theIndexIsIdempotentAndRebuiltFromTheCanonicalData() throws Exception {
        SearchHistory history = history("a");
        history.recordResource(received("https://api.example/x", "application/json", "{\"k\":\"rebuildterm\"}"), "start");
        assertEquals(1, history.search("rebuildterm", 5).size());
        history.rebuildIndex();
        history.rebuildIndex();
        assertEquals(1, history.search("rebuildterm", 5).size(), "rebuilding twice adds no duplicate");
        SearchHistory.awaitIndexing();
        Path lucene = history.repository().luceneDirectory();
        try (Stream<Path> files = Files.list(lucene)) {
            for (Path file : files.toList()) Files.write(file, new byte[] {1, 2, 3});
        }
        SearchHistory reopened = history("a");
        assertEquals(1, reopened.search("rebuildterm", 5).size(), "a damaged index is rebuilt");
        SearchHistoryTest.deleteTree(lucene);
        assertEquals(1, history("a").search("rebuildterm", 5).size(), "a deleted index is rebuilt");
    }

    @Test void testH_exportsKeepTheUrlListAndCarryResourcesWithManifest() throws Exception {
        SearchHistory history = history("a", Map.of(ResourceCategory.IMAGES, true));
        history.recordPage(page("https://site.example/", "Site", "Site text"), "start", "web_open", true);
        history.recordResource(received("https://site.example/api", "application/json", "{\"k\":\"exportterm\"}"),
                "start");
        byte[] image = new byte[3 * 1024 * 1024];
        image[0] = (byte) 0x89;
        history.recordResource(new SearchHistory.Received("https://site.example/big.png", "https://site.example/",
                200, "image/png", "image", image, (long) image.length, null, false), "start");
        SearchHistoryExporter exporter = new SearchHistoryExporter(history.repository());

        assertEquals(List.of("https://site.example/"), Files.readAllLines(exporter.export(
                SearchHistoryExporter.Format.TXT, false, true).file()), "no resource URLs in the URL list");

        List<String> lines = Files.readAllLines(exporter.export(SearchHistoryExporter.Format.JSONL, false, false).file());
        assertEquals(3, lines.size());
        ObjectMapper json = new ObjectMapper();
        JsonNode text = json.readTree(lines.get(1));
        assertEquals("network_resource", text.get("type").asText());
        assertTrue(text.get("body").asText().contains("exportterm"));
        JsonNode binary = json.readTree(lines.get(2));
        assertNull(binary.get("body"), "no base64 of binaries in JSONL");
        assertEquals(image.length, binary.get("size").asLong());
        assertTrue(binary.get("bodyRef").asText().startsWith("resources/"));
        assertTrue(lines.get(2).length() < 2_000, "the binary record is metadata only");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(exporter.export(SearchHistoryExporter.Format.ZIP, false, false)
                .file()); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertTrue(entries.containsKey("index.html"));
        assertTrue(new String(entries.get("Site.html"), StandardCharsets.UTF_8).contains("Textbasierter Fallback"));
        JsonNode manifest = json.readTree(entries.get("resources/manifest.json"));
        assertEquals(2, manifest.size());
        for (JsonNode row : manifest) {
            assertTrue(entries.containsKey(row.get("file").asText()), row.toString());
            for (String field : List.of("url", "category", "size", "sha256", "capturedAt", "mimeType")) {
                assertTrue(row.hasNonNull(field), field);
            }
        }
        JsonNode png = manifest.get(1);
        assertEquals("images", png.get("category").asText());
        assertArrayEquals(image, entries.get(png.get("file").asText()), "the received bytes, unchanged");
        assertEquals(SearchHistoryRepository.sha256(image), png.get("sha256").asText());
    }

    @Test void testI_theArchiveToolHandsOutARealHtmlDownloadAndExportsUseIt() throws Exception {
        SearchHistory history = history("a");
        SearchHistoryDownloads downloads = new SearchHistoryDownloads(Duration.ofMinutes(60),
                java.time.Clock.systemUTC());
        downloads.origin("https://argos.example");
        List<String> evaluated = new ArrayList<>();
        SearchHistoryCapture capture = new SearchHistoryCapture(history, new SearchHistoryCapture.LivePages() {
            @Override
            public com.aresstack.argos.web.PageCapture capture(String tabId) {
                return com.aresstack.argos.web.PageText.capture("https://site.example/",
                        "<title>Site</title><p>Site text</p>", 200);
            }

            @Override
            public Object evaluate(String function, String tabId) {
                evaluated.add(tabId);
                return "{\"title\":\"Site\",\"url\":\"https://site.example/\",\"html\":"
                        + "\"<!DOCTYPE html><html><body><p>archived copy</p><script>x()</script></body></html>\"}";
            }
        }, 1_000);
        capture.browserPage(null, "https://site.example/", true, "browser_navigate");
        new SearchHistoryExporter(history.repository()).export(SearchHistoryExporter.Format.ZIP, false, false);
        assertTrue(evaluated.isEmpty(), "navigation and export never run the collector");

        SyncToolSpecification tool = new SearchHistoryTools(history, capture::archiveHtml, downloads).specifications()
                .stream().filter(t -> t.tool().name().equals("browser_archive_html")).findFirst().orElseThrow();
        assertTrue(tool.tool().description().contains("ONLY when the user explicitly asks"));
        assertTrue(tool.tool().description().contains("request the page's images and stylesheets again"));
        CallToolResult result = tool.callHandler().apply(null,
                new CallToolRequest("browser_archive_html", Map.of("tab", "t2")));
        assertNotEquals(Boolean.TRUE, result.isError(), result.toString());
        assertEquals(List.of("t2"), evaluated, "the collector ran exactly once, in the requested tab");
        ResourceLink link = (ResourceLink) result.content().get(1);
        assertEquals("text/html; charset=utf-8", link.mimeType());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains(link.uri()) && !text.contains(root.toString()), text);
        SearchHistoryDownloads.Download download = downloads.resolve(link.uri().substring(
                link.uri().lastIndexOf('/') + 1)).orElseThrow();
        assertEquals("text/html; charset=utf-8", download.contentType());
        String html = Files.readString(download.file());
        assertTrue(html.contains("archived copy") && !html.contains("<script"), html);

        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(new SearchHistoryExporter(history.repository())
                .export(SearchHistoryExporter.Format.ZIP, false, false).file()); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertTrue(entries.get("Site.html").contains("archived copy"), "the explicit archive is used by later zips");
        assertEquals(1, evaluated.size(), "the export did not run the collector");
    }

    @Test void aCollectorTimeoutKeepsTheTextHistory() throws Exception {
        SearchHistory history = history("a");
        SearchHistoryCapture capture = new SearchHistoryCapture(history, new SearchHistoryCapture.LivePages() {
            @Override
            public com.aresstack.argos.web.PageCapture capture(String tabId) {
                return com.aresstack.argos.web.PageText.capture("https://slow.example/",
                        "<title>Slow</title><p>slowtext</p>", 200);
            }

            @Override
            public Object evaluate(String function, String tabId) {
                return ""; // the collector's own timeout answer
            }
        }, 1_000);
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> capture.archiveHtml(null));
        assertTrue(failed.getMessage().contains("page text is in the history"), failed.getMessage());
        assertEquals(1, history.search("slowtext", 5).size());
        try (Stream<Path> files = Files.list(history.repository().root().resolve("pages"))) {
            assertTrue(files.noneMatch(f -> f.toString().endsWith(".html")));
        }
    }

    @Test void testJ_captureSettingsToolChangesOnlyTheCallingChatAndResets() throws Exception {
        SearchHistory chatA = history("a", Map.of(ResourceCategory.PDF, true));
        SearchHistory chatB = history("b", Map.of(ResourceCategory.PDF, true));
        SyncToolSpecification tool = new SearchHistoryTools(chatA, tab -> null, new SearchHistoryDownloads(
                Duration.ofMinutes(1), java.time.Clock.systemUTC())).specifications().stream()
                .filter(t -> t.tool().name().equals("search_history_capture_settings")).findFirst().orElseThrow();
        String shown = call(tool, Map.of());
        assertTrue(shown.contains("- pdf: on (server setting SEARCH_HISTORY_CAPTURE_PDF)"), shown);
        assertTrue(shown.contains("- images: off"), shown);

        String changed = call(tool, Map.of("set", Map.of("images", true, "pdf", false)));
        assertTrue(changed.contains("- images: on (changed in this chat)"), changed);
        assertTrue(changed.contains("- pdf: off (changed in this chat)"), changed);
        assertTrue(Files.isRegularFile(root.resolve("a/history/capture-settings.json")));
        assertNotNull(chatA.recordResource(received("https://x.example/i.png", "image/png", "img"), "start"));
        assertFalse(chatB.captureSettings().enabled(ResourceCategory.IMAGES), "chat B is untouched");
        assertTrue(history("a").captureSettings().enabled(ResourceCategory.IMAGES), "persisted for the chat");

        String reset = call(tool, Map.of("reset", true));
        assertTrue(reset.contains("- images: off (server setting") && reset.contains("- pdf: on (server setting"), reset);
        assertFalse(Files.exists(root.resolve("a/history/capture-settings.json")));
        CallToolResult invalid = tool.callHandler().apply(null, new CallToolRequest("search_history_capture_settings",
                Map.of("set", Map.of("content", false))));
        assertTrue(invalid.isError(), "page content cannot be switched off");
        assertTrue(resources(chatA).size() == 1, "changing settings fetched nothing");
    }

    private static String call(SyncToolSpecification tool, Map<String, Object> arguments) {
        CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(tool.tool().name(), arguments));
        assertNotEquals(Boolean.TRUE, result.isError(), result.toString());
        return ((TextContent) result.content().get(0)).text();
    }

    private static ResponseContentSink.ObservedResponse observed(String type, Long length, boolean encoded) {
        return new ResponseContentSink.ObservedResponse("https://cdn.example/x", "https://page.example/", "GET", 200,
                type, ResourceCategory.normalize(type), "fetch", length, null, encoded);
    }
}
