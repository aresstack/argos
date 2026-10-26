package com.aresstack.argos.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns downloaded bytes into text, independent of the browser. PDF uses Apache PDFBox, HTML uses jsoup,
 * Office Open XML and OpenDocument files are read with the JDK's ZIP and StAX support. Legacy binary Office
 * formats (.doc, .xls, .ppt) are reported as unsupported instead of pulling in Apache POI.
 */
public final class DocumentTextExtractor {
    private static final int MAX_PDF_PAGES = 500;

    public DocumentText extract(byte[] content, String declaredType, String filename, int maxCharacters) {
        DocumentFormat format = DocumentFormat.detect(declaredType, filename, content);
        try {
            return switch (format) {
                case PDF -> pdf(content, maxCharacters);
                case HTML -> limited(format, html(content, declaredType), 0, maxCharacters);
                case XML, JSON, CSV, TEXT -> limited(format, decode(content, declaredType), 0, maxCharacters);
                case DOCX, XLSX, PPTX, ODT, ODS, ODP ->
                        limited(format, ZippedXmlText.read(format, content), 0, maxCharacters);
                case UNSUPPORTED -> DocumentText.unreadable(format,
                        "Unsupported document type" + (declaredType == null || declaredType.isBlank()
                                ? "" : " (" + declaredType.toLowerCase(Locale.ROOT) + ")"));
            };
        } catch (InvalidPasswordException locked) {
            return DocumentText.unreadable(format, "The document is password protected");
        } catch (Exception broken) {
            return DocumentText.unreadable(format, "The " + format.name() + " content could not be parsed");
        }
    }

    private static DocumentText pdf(byte[] content, int maxCharacters) throws Exception {
        try (PDDocument document = Loader.loadPDF(content)) {
            int pages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setEndPage(Math.min(pages, MAX_PDF_PAGES));
            String text = stripper.getText(document);
            if (text.isBlank()) {
                return new DocumentText(DocumentFormat.PDF, false, "", false, pages,
                        "The PDF contains no text layer (scanned images need OCR, which Argos does not run)");
            }
            DocumentText limited = limited(DocumentFormat.PDF, text, pages, maxCharacters);
            return pages > MAX_PDF_PAGES
                    ? new DocumentText(limited.format(), true, limited.text(), true, pages,
                    "Only the first " + MAX_PDF_PAGES + " pages were read")
                    : limited;
        }
    }

    private static String html(byte[] content, String declaredType) {
        var document = Jsoup.parse(decode(content, declaredType));
        String title = document.title();
        String body = document.body() == null ? document.text() : document.body().wholeText();
        body = body.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").strip();
        return title.isBlank() ? body : title + "\n\n" + body;
    }

    static String decode(byte[] content, String declaredType) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            return new String(content, StandardCharsets.UTF_16);
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            return new String(content, StandardCharsets.UTF_16);
        }
        return new String(content, charsetOf(declaredType));
    }

    private static Charset charsetOf(String declaredType) {
        if (declaredType != null) {
            for (String parameter : declaredType.split(";")) {
                String item = parameter.trim();
                if (item.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(item.substring(8).replace("\"", "").trim());
                    } catch (Exception unknown) {
                        break;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static DocumentText limited(DocumentFormat format, String text, int pages, int maxCharacters) {
        String clean = text.replace("\u0000", "");
        boolean truncated = clean.length() > maxCharacters;
        return new DocumentText(format, true, truncated ? clean.substring(0, maxCharacters) : clean,
                truncated, pages, truncated ? "Text truncated to " + maxCharacters + " characters" : "");
    }
}
