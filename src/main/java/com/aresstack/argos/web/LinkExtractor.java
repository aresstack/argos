package com.aresstack.argos.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts followable links from serialized HTML on the Java side, so the page never
 * sees an extra script. Keeps document order, resolves relative URLs, drops
 * non-http(s) targets and same-page fragments, and merges duplicates by URL.
 */
public final class LinkExtractor {
    private static final int MAX_TEXT = 120;

    public record Anchor(String text, String url) {
    }

    public List<Anchor> extract(String html, String pageUrl) {
        if (html == null || html.isBlank()) return List.of();
        Document document = Jsoup.parse(html, pageUrl == null ? "" : pageUrl);
        String page = withoutFragment(pageUrl == null ? "" : pageUrl);
        Map<String, String> byUrl = new LinkedHashMap<>();
        for (Element anchor : document.select("a[href]")) {
            String url = anchor.absUrl("href");
            String scheme = url.contains(":") ? url.substring(0, url.indexOf(':')).toLowerCase(Locale.ROOT) : "";
            if (!scheme.equals("http") && !scheme.equals("https")) continue;
            if (withoutFragment(url).equals(page) && url.contains("#")) continue;
            String text = label(anchor);
            String known = byUrl.get(url);
            if (known == null || (known.isEmpty() && !text.isEmpty())) byUrl.put(url, text);
        }
        List<Anchor> anchors = new ArrayList<>(byUrl.size());
        byUrl.forEach((url, text) -> anchors.add(new Anchor(text, url)));
        return anchors;
    }

    private static String label(Element anchor) {
        String text = anchor.text().strip();
        if (text.isEmpty()) text = anchor.attr("aria-label").strip();
        if (text.isEmpty()) text = anchor.attr("title").strip();
        if (text.isEmpty()) {
            Element image = anchor.selectFirst("img[alt]");
            if (image != null) text = image.attr("alt").strip();
        }
        text = text.replaceAll("\\s+", " ");
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT - 1) + "…" : text;
    }

    private static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }
}
