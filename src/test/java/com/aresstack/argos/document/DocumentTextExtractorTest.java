package com.aresstack.argos.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorTest {
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void readsPdfTextAndPageCount() throws Exception {
        DocumentText text = extractor.extract(TestDocuments.pdf("Attention is all you need", "Second page"),
                "application/pdf", "paper.pdf", 10_000);
        assertEquals(DocumentFormat.PDF, text.format());
        assertTrue(text.readable());
        assertEquals(2, text.pages());
        assertTrue(text.text().contains("Attention is all you need"));
        assertTrue(text.text().contains("Second page"));
    }

    @Test
    void recognisesPdfDeclaredAsOctetStream() throws Exception {
        DocumentText text = extractor.extract(TestDocuments.pdf("Hidden PDF"), "application/octet-stream",
                "download", 10_000);
        assertEquals(DocumentFormat.PDF, text.format());
        assertTrue(text.text().contains("Hidden PDF"));
    }

    @Test
    void truncatesToTheLimit() throws Exception {
        DocumentText text = extractor.extract("x".repeat(5_000).getBytes(StandardCharsets.UTF_8),
                "text/plain", "a.txt", 1_000);
        assertTrue(text.truncated());
        assertEquals(1_000, text.text().length());
    }

    @Test
    void readsDocxParagraphs() throws Exception {
        DocumentText text = extractor.extract(TestDocuments.docx("Erster Absatz", "Zweiter Absatz"),
                "", "bericht.docx", 10_000);
        assertEquals(DocumentFormat.DOCX, text.format());
        assertEquals("Erster Absatz\nZweiter Absatz", text.text());
    }

    @Test
    void readsXlsxCellsWithSharedStrings() throws Exception {
        byte[] xlsx = TestDocuments.zip(Map.of(
                "xl/sharedStrings.xml", "<sst><si><t>Name</t></si><si><t>Wert</t></si><si><t>Alpha</t></si></sst>",
                "xl/worksheets/sheet1.xml", "<worksheet><sheetData>"
                        + "<row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c></row>"
                        + "<row><c t=\"s\"><v>2</v></c><c><v>42</v></c></row></sheetData></worksheet>"));
        DocumentText text = extractor.extract(xlsx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "t.xlsx", 10_000);
        assertEquals("--- Sheet 1 ---\nName\tWert\nAlpha\t42", text.text());
    }

    @Test
    void keepsSparseSpreadsheetCellsInTheirColumns() throws Exception {
        byte[] xlsx = TestDocuments.zip(Map.of(
                "xl/sharedStrings.xml", "<sst><si><t>Name</t></si><si><t>Revenue</t></si></sst>",
                "xl/worksheets/sheet1.xml", "<worksheet><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>2025</v></c>"
                        + "<c r=\"C1\"><v>2026</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"C2\"><v>42</v></c></row>"
                        + "<row r=\"3\"><c r=\"B3\"><v>7</v></c></row></sheetData></worksheet>"));
        assertEquals("--- Sheet 1 ---\nName\t2025\t2026\nRevenue\t\t42\n\t7",
                extractor.extract(xlsx, "", "t.xlsx", 10_000).text());
    }

    @Test
    void countsIgnoredArchiveEntriesAgainstTheUnpackLimit() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/media/padding.bin"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 65; i++) zip.write(zeros);
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("<w:document xmlns:w=\"w\"><w:p><w:t>x</w:t></w:p></w:document>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        DocumentText text = extractor.extract(out.toByteArray(), "", "bomb.docx", 10_000);
        assertFalse(text.readable());
    }

    @Test
    void readsPptxSlidesInOrder() throws Exception {
        String ns = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"";
        byte[] pptx = TestDocuments.zip(Map.of(
                "ppt/slides/slide10.xml", "<sld " + ns + "><a:p><a:r><a:t>Ten</a:t></a:r></a:p></sld>",
                "ppt/slides/slide2.xml", "<sld " + ns + "><a:p><a:r><a:t>Two</a:t></a:r></a:p></sld>"));
        String text = extractor.extract(pptx, "", "deck.pptx", 10_000).text();
        assertTrue(text.indexOf("Two") < text.indexOf("Ten"), text);
    }

    @Test
    void readsOpenDocumentText() throws Exception {
        byte[] odt = TestDocuments.zip(Map.of("content.xml",
                "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                        + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"><office:body><office:text>"
                        + "<text:h>Titel</text:h><text:p>Inhalt</text:p></office:text></office:body>"
                        + "</office:document-content>"));
        assertEquals("Titel\nInhalt", extractor.extract(odt, "", "a.odt", 10_000).text());
    }

    @Test
    void refusesExternalEntitiesInOfficeXml() throws Exception {
        byte[] hostile = TestDocuments.zip(Map.of("word/document.xml",
                "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                        + "<w:document xmlns:w=\"w\"><w:p><w:t>&x;</w:t></w:p></w:document>"));
        DocumentText text = extractor.extract(hostile, "", "evil.docx", 10_000);
        assertFalse(text.text().contains("root:"));
    }

    @Test
    void returnsXmlRssAndJsonAsText() {
        String rss = "<?xml version=\"1.0\"?><rss><channel><item><title>News</title></item></channel></rss>";
        DocumentText xml = extractor.extract(rss.getBytes(StandardCharsets.UTF_8), "application/rss+xml",
                "feed", 10_000);
        assertEquals(DocumentFormat.XML, xml.format());
        assertTrue(xml.text().contains("<title>News</title>"));
        DocumentText json = extractor.extract("{\"a\":1}".getBytes(StandardCharsets.UTF_8), "application/json",
                "", 10_000);
        assertEquals("{\"a\":1}", json.text());
    }

    @Test
    void readsHtmlWithoutScripts() {
        String html = "<html><head><title>T</title><script>var secret=1</script></head>"
                + "<body><h1>Kopf</h1><p>Absatz</p></body></html>";
        DocumentText text = extractor.extract(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8",
                "", 10_000);
        assertTrue(text.text().startsWith("T\n\n"));
        assertTrue(text.text().contains("Kopf"));
        assertFalse(text.text().contains("secret"));
    }

    @Test
    void decodesDeclaredCharset() {
        byte[] latin1 = "Größe".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("Größe", extractor.extract(latin1, "text/csv; charset=ISO-8859-1", "a.csv", 100).text());
    }

    @Test
    void reportsBinaryAndBrokenContentAsUnreadable() {
        DocumentText binary = extractor.extract(new byte[]{1, 0, 2, 0}, "application/octet-stream", "x.bin", 100);
        assertFalse(binary.readable());
        assertTrue(binary.note().startsWith("Unsupported document type"));
        DocumentText broken = extractor.extract("%PDF-1.7 broken".getBytes(StandardCharsets.US_ASCII),
                "application/pdf", "x.pdf", 100);
        assertFalse(broken.readable());
        assertEquals(DocumentFormat.PDF, broken.format());
    }
}
