package com.aresstack.argos.page;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns the raw source of non-HTML documents into text a model can read.
 * Pure functions: no browser access, so every format is unit-testable.
 */
public final class DocumentText {
    /** JSON larger than this is returned as-is instead of being re-indented. */
    private static final int MAX_PRETTY_PRINT_JSON = 2_000_000;
    private static final int MAX_FEED_SUMMARY = 300;
    private static final Pattern TAGS = Pattern.compile("<[^>]{0,2000}>");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DocumentText() { }

    /** Feeds become a numbered entry list; any other XML is returned as its source. */
    public static String xml(String source) {
        String trimmed = stripByteOrderMark(source).trim();
        return feed(trimmed).orElse(trimmed);
    }

    /** Pretty-printed JSON when parseable, otherwise the unchanged text. */
    public static String json(String source) {
        String trimmed = stripByteOrderMark(source).trim();
        if (trimmed.length() > MAX_PRETTY_PRINT_JSON) return trimmed;
        try {
            JsonNode tree = JSON.readTree(trimmed);
            return tree == null ? trimmed : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (Exception notJson) {
            return trimmed;
        }
    }

    /** Readable RSS 2.0 / RSS 1.0 (RDF) / Atom listing, or empty if the XML is not a feed. */
    public static Optional<String> feed(String source) {
        Document document = parse(source);
        if (document == null) return Optional.empty();
        Element root = document.getDocumentElement();
        String rootName = localName(root);
        Element channel;
        List<Element> entries;
        if ("rss".equals(rootName)) {
            channel = child(root, "channel");
            entries = channel == null ? List.of() : children(channel, "item");
        } else if ("RDF".equals(rootName)) {
            channel = child(root, "channel");
            entries = children(root, "item");
        } else if ("feed".equals(rootName)) {
            channel = root;
            entries = children(root, "entry");
        } else {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        String feedTitle = channel == null ? "" : childText(channel, "title");
        String feedDescription = channel == null ? ""
                : firstNonBlank(childText(channel, "description"), childText(channel, "subtitle"));
        text.append("Feed: ").append(feedTitle.isBlank() ? "(untitled)" : feedTitle).append('\n');
        if (!feedDescription.isBlank()) text.append(plain(feedDescription)).append('\n');
        text.append(entries.size()).append(entries.size() == 1 ? " entry" : " entries").append('\n');
        int number = 1;
        for (Element entry : entries) {
            text.append('\n').append(number++).append(". ")
                    .append(firstNonBlank(plain(childText(entry, "title")), "(untitled)")).append('\n');
            String link = entryLink(entry);
            if (!link.isBlank()) text.append("   ").append(link).append('\n');
            String date = firstNonBlank(childText(entry, "pubDate"), childText(entry, "published"),
                    childText(entry, "updated"), childText(entry, "date"));
            if (!date.isBlank()) text.append("   ").append(date.trim()).append('\n');
            String summary = plain(firstNonBlank(childText(entry, "description"),
                    childText(entry, "summary"), childText(entry, "content")));
            if (!summary.isBlank()) {
                if (summary.length() > MAX_FEED_SUMMARY) summary = summary.substring(0, MAX_FEED_SUMMARY) + "…";
                text.append("   ").append(summary).append('\n');
            }
        }
        return Optional.of(text.toString().trim());
    }

    private static String entryLink(Element entry) {
        String alternate = "";
        for (Element link : children(entry, "link")) {
            String href = link.getAttribute("href");
            if (href.isBlank()) return link.getTextContent().trim(); // RSS: <link>url</link>
            String rel = link.getAttribute("rel");
            if (rel.isBlank() || "alternate".equals(rel)) return href.trim();
            if (alternate.isBlank()) alternate = href.trim();
        }
        return firstNonBlank(alternate, childText(entry, "guid"), childText(entry, "id"));
    }

    /** Hardened parser: no DTD fetching, no external entities (feeds come from arbitrary sites). */
    private static Document parse(String source) {
        if (source == null || !source.startsWith("<")) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new InputSource(new StringReader(source)));
        } catch (Exception notWellFormed) {
            return null;
        }
    }

    private static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> matches = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && name.equals(localName(element))) matches.add(element);
        }
        return matches;
    }

    private static Element child(Element parent, String name) {
        List<Element> matches = children(parent, name);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static String childText(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent();
    }

    /** Feed fields often carry escaped HTML; reduce it to one line of plain text. */
    static String plain(String markup) {
        if (markup == null) return "";
        String text = TAGS.matcher(markup).replaceAll(" ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return SPACES.matcher(text).replaceAll(" ").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String stripByteOrderMark(String source) {
        if (source == null) return "";
        return source.startsWith("﻿") ? source.substring(1) : source;
    }
}
