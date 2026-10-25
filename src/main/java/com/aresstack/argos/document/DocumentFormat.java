package com.aresstack.argos.document;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** The document formats Argos can turn into text, recognised from declared type, file name and content. */
public enum DocumentFormat {
    PDF("application/pdf", ".pdf"),
    HTML("text/html", ".html"),
    XML("application/xml", ".xml"),
    JSON("application/json", ".json"),
    CSV("text/csv", ".csv"),
    TEXT("text/plain", ".txt"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
    ODT("application/vnd.oasis.opendocument.text", ".odt"),
    ODS("application/vnd.oasis.opendocument.spreadsheet", ".ods"),
    ODP("application/vnd.oasis.opendocument.presentation", ".odp"),
    UNSUPPORTED("application/octet-stream", ".bin");

    private final String mediaType;
    private final String extension;

    DocumentFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    /**
     * Decides the format. PDF content is always recognised; otherwise a specific declared media type wins; a generic or missing one
     * ({@code application/octet-stream}, {@code binary/*}) falls back to the file name and the first bytes.
     */
    public static DocumentFormat detect(String declaredType, String filename, byte[] content) {
        String type = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) type = type.substring(0, semicolon);
        type = type.trim();
        if (content != null && startsWith(content, "%PDF-")) return PDF; // misdeclared PDFs are common
        DocumentFormat declared = fromMediaType(type);
        if (declared != null) return declared;
        DocumentFormat byName = fromFilename(filename);
        if (byName != null) return byName;
        return sniff(content);
    }

    private static DocumentFormat fromMediaType(String type) {
        for (DocumentFormat format : values()) {
            if (format != UNSUPPORTED && format.mediaType.equals(type)) return format;
        }
        if (type.equals("application/x-pdf")) return PDF;
        if (type.equals("application/xhtml+xml")) return HTML;
        if (type.equals("text/xml") || type.endsWith("+xml")) return XML;
        if (type.endsWith("+json") || type.equals("text/json")) return JSON;
        if (type.equals("text/markdown") || type.equals("text/x-markdown")) return TEXT;
        if (type.startsWith("text/") && !type.equals("text/html")) return TEXT;
        return null;
    }

    private static DocumentFormat fromFilename(String filename) {
        if (filename == null) return null;
        String name = filename.toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            if (format != UNSUPPORTED && name.endsWith(format.extension)) return format;
        }
        if (name.endsWith(".htm") || name.endsWith(".xhtml")) return HTML;
        if (name.endsWith(".rss") || name.endsWith(".atom")) return XML;
        if (name.endsWith(".md") || name.endsWith(".log")) return TEXT;
        return null;
    }

    private static DocumentFormat sniff(byte[] content) {
        if (content == null || content.length == 0) return TEXT;
        if (startsWith(content, "%PDF-")) return PDF;
        if (startsWith(content, "PK\u0003\u0004")) return UNSUPPORTED; // a ZIP needs its name to be read
        String head = new String(content, 0, Math.min(content.length, 512), StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("﻿")) head = head.substring(1);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) return HTML;
        if (head.startsWith("<?xml") || head.startsWith("<rss") || head.startsWith("<feed")) return XML;
        if (head.startsWith("{") || head.startsWith("[")) return JSON;
        for (int i = 0; i < Math.min(content.length, 1024); i++) {
            if (content[i] == 0) return UNSUPPORTED;
        }
        return TEXT;
    }

    private static boolean startsWith(byte[] content, String prefix) {
        if (content.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (content[i] != (byte) prefix.charAt(i)) return false;
        }
        return true;
    }
}
