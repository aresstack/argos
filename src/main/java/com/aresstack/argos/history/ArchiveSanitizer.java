package com.aresstack.argos.history;

import com.aresstack.argos.network.SecretMasker;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cleans an archived page before it is stored: form values (passwords, hidden tokens, pre-filled fields), inline
 * event handlers, {@code javascript:} links and meta refreshes go, and secret query values in link targets are
 * masked. Runs in the JVM on the collector's result, never in the page.
 */
final class ArchiveSanitizer {
    private static final Set<String> KEEP_VALUE_TYPES = Set.of("submit", "button", "reset", "image");
    private static final SecretMasker MASKER = new SecretMasker();

    private ArchiveSanitizer() {
    }

    static String sanitize(String html, String baseUrl) {
        Document document = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        document.select("script, meta[http-equiv~=(?i)refresh]").remove();
        for (Element input : document.select("input")) {
            String type = input.attr("type").toLowerCase(Locale.ROOT);
            if (!KEEP_VALUE_TYPES.contains(type) || SecretMasker.isSecretName(input.attr("name"))) {
                input.removeAttr("value");
            }
        }
        document.select("textarea").forEach(area -> area.text(""));
        for (Element element : document.getAllElements()) {
            List<String> drop = new ArrayList<>();
            for (Attribute attribute : element.attributes()) {
                String key = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = attribute.getValue().strip().toLowerCase(Locale.ROOT);
                if (key.startsWith("on") || (key.equals("href") || key.equals("src") || key.equals("action"))
                        && value.startsWith("javascript:")) {
                    drop.add(attribute.getKey());
                }
            }
            drop.forEach(element::removeAttr);
        }
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href");
            if (href.startsWith("http://") || href.startsWith("https://")) anchor.attr("href", safeUrl(href));
        }
        document.outputSettings().prettyPrint(false);
        return document.outerHtml();
    }

    /**
     * User info removed and secret query values masked. A plain fragment is kept for in-page anchors; one that
     * carries parameters (e.g. an OAuth {@code #access_token=...}) is dropped.
     */
    static String safeUrl(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            java.net.URI.create(url.strip());
        } catch (IllegalArgumentException unparseable) {
            return url;
        }
        int hash = url.indexOf('#');
        String fragment = hash >= 0 ? url.substring(hash) : "";
        if (fragment.contains("=")) fragment = "";
        String masked = MASKER.url(url.strip());
        return masked.startsWith("http") ? masked + fragment : url;
    }
}
