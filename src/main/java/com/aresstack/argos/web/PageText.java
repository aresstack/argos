package com.aresstack.argos.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.Locale;
import java.util.Set;

/**
 * Turns the serialized DOM into readable text on the Java side. One page.content() call
 * is all the browser has to answer; every extra in-page read (innerText, title, count)
 * is another protocol evaluation the page could notice.
 */
public final class PageText {
    private static final Set<String> SKIPPED = Set.of(
            "script", "style", "noscript", "template", "svg", "canvas", "iframe", "head");
    private static final Set<String> BLOCKS = Set.of(
            "address", "article", "aside", "blockquote", "br", "dd", "details", "div", "dl", "dt",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header",
            "hr", "li", "main", "nav", "ol", "p", "pre", "section", "summary", "table", "tr", "ul");

    private PageText() {
    }

    /** A capture whose title and text are derived from {@code html}. */
    public static PageCapture capture(String url, String html) {
        return capture(url, html, 0);
    }

    /** Like {@link #capture(String, String)}, with the HTTP status of the document's response (0 = unknown). */
    public static PageCapture capture(String url, String html, int status) {
        Document document = Jsoup.parse(html == null ? "" : html, url == null ? "" : url);
        return new PageCapture(url, document.title(), text(document), html, status);
    }

    static String text(Document document) {
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            private int hiddenDepth;

            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element element) {
                    if (hiddenDepth > 0 || hidden(element)) {
                        hiddenDepth++;
                    } else if (BLOCKS.contains(element.normalName())) {
                        out.append('\n');
                    } else if (element.normalName().equals("td") || element.normalName().equals("th")) {
                        out.append(' ');
                    }
                } else if (node instanceof TextNode text && hiddenDepth == 0) {
                    out.append(text.getWholeText().replaceAll("\\s+", " "));
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element) {
                    if (hiddenDepth > 0) hiddenDepth--;
                    else if (BLOCKS.contains(element.normalName())) out.append('\n');
                }
            }
        }, document);
        return out.toString()
                .replaceAll("[ \\t]*\\n[ \\t]*", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static boolean hidden(Element element) {
        if (SKIPPED.contains(element.normalName())) return true;
        if (element.hasAttr("hidden") || "true".equalsIgnoreCase(element.attr("aria-hidden"))) return true;
        String style = element.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
        return style.contains("display:none") || style.contains("visibility:hidden");
    }
}
