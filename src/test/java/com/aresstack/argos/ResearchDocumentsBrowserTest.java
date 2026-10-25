package com.aresstack.argos;

import com.aresstack.argos.document.DocumentStore;
import com.aresstack.argos.document.StoredDocument;
import com.aresstack.argos.fetch.FetchFailure;
import com.aresstack.argos.fetch.FixtureSite;
import com.aresstack.argos.fetch.TestBrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in: needs -PtestCamoufox=true or -PtestChromium=/path/to/chrome. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ResearchDocumentsBrowserTest {
    private TestBrowserSession browser;
    private FixtureSite site;
    private ResearchDocuments documents;

    // A @TempDir field is not yet injected when a PER_CLASS @BeforeAll runs; the parameter is.
    @BeforeAll
    void startBrowser(@TempDir Path temporary) throws Exception {
        browser = TestBrowserSession.start(temporary).orElse(null);
        Assumptions.assumeTrue(browser != null, "Opt-in real browser test");
        site = new FixtureSite();
        documents = new ResearchDocuments(browser, new DocumentStore(
                32 * 1024, 1024 * 1024, 20, Duration.ofMinutes(5), Clock.systemUTC()), FixtureSite.testPolicy());
    }

    @AfterAll
    void stopBrowser() {
        if (documents != null) documents.close();
        if (browser != null) browser.close();
        if (site != null) site.close();
    }

    @Test
    void downloadsPdfLinkedFromCurrentPageWithoutLeavingIt() {
        browser.openCurrent(site.url("/article.html"));

        StoredDocument document = documents.download("paper.pdf");
        Map<String, Object> read = documents.read(document.downloadId(), 5_000);

        assertEquals(site.url("/paper.pdf"), document.sourceUrl());
        assertEquals("paper.pdf", document.filename());
        assertEquals("PDF", read.get("format"));
        assertEquals(true, read.get("readable"));
        assertTrue(read.get("text").toString().contains("Argos fixture paper about background downloads"));
        assertEquals(site.url("/article.html"), browser.currentUrl());
        assertTrue(browser.currentText().contains("Research article"));
    }

    @Test
    void readsAttachmentDownloadsWithTheirServerFilename() {
        StoredDocument document = documents.download(site.url("/attachment"));
        Map<String, Object> read = documents.read(document.downloadId(), 5_000);

        assertEquals("bericht.docx", document.filename());
        assertEquals("DOCX", read.get("format"));
        assertTrue(read.get("text").toString().contains("Inhalt des Berichts"), read.toString());
    }

    @Test
    void refusesDocumentsAboveTheSizeLimitAndMissingOnes() {
        FetchFailure large = assertThrows(FetchFailure.class, () -> documents.download(site.url("/large.bin")));
        assertEquals(FetchFailure.Kind.TOO_LARGE, large.kind());
        FetchFailure missing = assertThrows(FetchFailure.class, () -> documents.download(site.url("/gone.pdf")));
        assertEquals(FetchFailure.Kind.NOT_FOUND, missing.kind());
        FetchFailure unknown = assertThrows(FetchFailure.class, () -> documents.read("doc-999", 1_000));
        assertEquals(FetchFailure.Kind.NOT_FOUND, unknown.kind());
    }

    @Test
    void blocksAddressesAndRedirectsTheUrlPolicyDoesNotAllow() {
        browser.openCurrent(site.url("/article.html"));
        FetchFailure direct = assertThrows(FetchFailure.class,
                () -> documents.download(site.loopbackUrl("/paper.pdf")));
        assertEquals(FetchFailure.Kind.BLOCKED, direct.kind());

        String redirect = site.url("/redirect?to=" + java.net.URLEncoder.encode(site.loopbackUrl("/paper.pdf"),
                java.nio.charset.StandardCharsets.UTF_8));
        FetchFailure redirected = assertThrows(FetchFailure.class, () -> documents.download(redirect));
        assertEquals(FetchFailure.Kind.BLOCKED, redirected.kind(), redirected.getMessage());

        Map<String, Object> batch = documents.openMany(List.of(redirect, site.url("/other.html")), 1_000);
        assertEquals(List.of("BLOCKED", "SUCCESS"), ((List<?>) batch.get("results")).stream()
                .map(result -> ((Map<?, ?>) result).get("status")).toList(), batch.toString());
        assertEquals(site.url("/article.html"), browser.currentUrl());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchKeepsGoodResultsWhenOneAddressIsBroken() throws Exception {
        browser.openCurrent(site.url("/article.html"));
        List<String> urls = List.of(site.url("/other.html"), site.url("/missing"), FixtureSite.refusedUrl(),
                site.url("/paper.pdf"), site.url("/feed.xml"), site.url("/other.html#again"), "javascript:alert(1)");

        Map<String, Object> batch = documents.openMany(urls, 2_000);
        List<Map<String, Object>> results = (List<Map<String, Object>>) batch.get("results");

        assertEquals(urls.size(), results.size());
        assertEquals(List.of("SUCCESS", "NOT_FOUND", "FAILED", "SUCCESS", "SUCCESS", "DUPLICATE", "INVALID_REQUEST"),
                results.stream().map(result -> result.get("status")).toList(), results.toString());
        assertTrue(results.get(0).get("text").toString().contains("Another page"));
        assertEquals("Other", results.get(0).get("title"));
        assertTrue(results.get(3).get("text").toString().contains("Argos fixture paper"));
        assertTrue(results.get(4).get("text").toString().contains("Feed item"));
        assertEquals(3L, batch.get("succeeded"));
        assertEquals(site.url("/article.html"), browser.currentUrl());
    }
}
