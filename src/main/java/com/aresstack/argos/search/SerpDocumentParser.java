package com.aresstack.argos.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Selector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link SerpDocument} from serialized page HTML. This is the Java-side replacement for
 * AskAI's in-page capture script: Argos obtains the HTML through Playwright's isolated utility world
 * ({@code page.content()}), so the page never sees an injected script, and everything below runs in
 * the JVM. Visibility is approximated from markup (hidden, aria-hidden, inline display/visibility).
 */
final class SerpDocumentParser {
    private static final Set<String> SKIP = Set.of(
            "script", "style", "noscript", "template", "svg", "iframe", "head", "link", "meta");
    private static final Set<String> CONTAINER = Set.of(
            "body", "div", "section", "main", "nav", "header", "footer", "aside", "article", "ul", "ol",
            "li", "form", "table", "tbody", "tr", "td", "dl", "dd", "dt", "fieldset", "details");
    private static final int EXCERPT = 600;
    private static final int LINK_TEXT = 300;

    private final SearchAnalysisSettings settings;
    private int containerSeq;
    private int linkSeq;
    private boolean truncated;
    private List<SerpContainer> containers;
    private List<SerpLink> links;
    private Map<Element, String> containerIds;
    private String pageUrl;

    SerpDocumentParser(SearchAnalysisSettings settings) {
        this.settings = settings;
    }

    /**
     * @param adapterBlockSelector CSS selector of result blocks known for this engine endpoint, or
     *                             blank when the page must be understood mechanically.
     */
    SerpDocument parse(String html, String url, String title, String adapterBlockSelector, long generation) {
        return parse(Jsoup.parse(html == null ? "" : html, url == null ? "" : url), url, title,
                adapterBlockSelector, generation);
    }

    synchronized SerpDocument parse(Document document, String url, String title, String adapterBlockSelector,
                                    long generation) {
        containerSeq = 0;
        linkSeq = 0;
        truncated = false;
        containers = new ArrayList<>();
        links = new ArrayList<>();
        containerIds = new IdentityHashMap<>();
        pageUrl = url == null ? "" : url;
        Element body = document.body();
        String visibleText = "";
        if (body != null) {
            walk(body, "", 0, 0, false);
            visibleText = textOf(body, Integer.MAX_VALUE);
        }
        String effectiveTitle = title == null || title.isBlank() ? document.title() : title;
        List<SerpContainer> withSiblings = withSimilarSiblingCounts(containers);
        return new SerpDocument("snap-" + generation + "-" + Integer.toHexString(visibleText.hashCode()),
                pageUrl, effectiveTitle, visibleText, withSiblings, links,
                adapterBlocks(document, adapterBlockSelector), truncated);
    }

    /** Running statistics of one container subtree. */
    private static final class Stats {
        int text;
        int linkText;
        int headings;
        int paragraphs;
        int linkCount;
        int sameHost;
        int subdomain;
        int sameRegistrable;
        int external;
        int action;

        void add(Stats other) {
            text += other.text;
            linkText += other.linkText;
            headings += other.headings;
            paragraphs += other.paragraphs;
            linkCount += other.linkCount;
            sameHost += other.sameHost;
            subdomain += other.subdomain;
            sameRegistrable += other.sameRegistrable;
            external += other.external;
            action += other.action;
        }
    }

    /** Per-container context for links found in its inline content. */
    private static final class Context {
        final Element containerElement;
        int links;
        String lastHeading = "";

        Context(Element containerElement) {
            this.containerElement = containerElement;
        }
    }

    private record WalkResult(String id, Stats stats) {
    }

    private WalkResult walk(Element element, String parentId, int depth, int siblingIndex, boolean hiddenAncestor) {
        if (containerSeq >= settings.maximumCapturedContainers() || depth > settings.maximumContainerDomDepth()) {
            truncated = true;
            return null;
        }
        String id = "container-" + String.format(Locale.ROOT, "%04d", ++containerSeq);
        containerIds.put(element, id);
        boolean hidden = hiddenAncestor || isHidden(element);
        Stats stats = new Stats();
        Context context = new Context(element);
        List<String> childIds = new ArrayList<>();
        int childIndex = 0;
        int placeholder = containers.size();
        containers.add(null); // keep document (pre-)order: parents before children
        for (Node child : element.childNodes()) {
            if (child instanceof Element childElement && isContainer(childElement)) {
                WalkResult result = walk(childElement, id, depth + 1, childIndex, hidden);
                if (result != null) {
                    childIds.add(result.id());
                    childIndex++;
                    stats.add(result.stats());
                    continue;
                }
            }
            collectInline(child, id, stats, context, hidden);
        }
        String role = element.attr("role").toLowerCase(Locale.ROOT);
        containers.set(placeholder, new SerpContainer(id, parentId, childIds, siblingIndex, depth,
                element.normalName(), element.id(), classTokens(element), role, semanticFlags(element, role),
                textOf(element, EXCERPT), stats.text, stats.linkText, stats.headings, stats.paragraphs,
                stats.linkCount, stats.sameHost, stats.subdomain, stats.sameRegistrable, stats.external,
                stats.action, !hidden && !textOf(element, 1).isEmpty(),
                signature(element, settings.maximumStructureSignatureDepth()), 0));
        return new WalkResult(id, stats);
    }

    private void collectInline(Node node, String containerId, Stats stats, Context context, boolean hidden) {
        if (node instanceof TextNode text) {
            if (!hidden) stats.text += clean(text.text()).length();
            return;
        }
        if (!(node instanceof Element element) || SKIP.contains(element.normalName())) return;
        boolean elementHidden = hidden || isHidden(element);
        String tag = element.normalName();
        if (tag.matches("h[1-6]")) {
            stats.headings++;
            context.lastHeading = textOf(element, 200);
        }
        if (tag.equals("p")) stats.paragraphs++;
        if (tag.equals("a") && element.hasAttr("href")) {
            int length = elementHidden ? 0 : textOf(element, Integer.MAX_VALUE).length();
            stats.text += length;
            stats.linkText += length;
            countLink(recordLink(element, containerId, context, elementHidden), stats);
            // Google wraps the title heading in the anchor: it still is a heading of this block.
            stats.headings += element.select("h1, h2, h3, h4, h5, h6").size();
            return;
        }
        for (Node child : element.childNodes()) collectInline(child, containerId, stats, context, elementHidden);
    }

    /** Records the link (bounded per container) and returns how its RESOLVED target relates to the page. */
    private DomainNames.DomainRelation recordLink(Element anchor, String containerId, Context context, boolean hidden) {
        String rawHref = anchor.absUrl("href");
        if (rawHref.isEmpty()) rawHref = anchor.attr("href");
        SearchRedirects.Resolution resolution = SearchRedirects.resolve(rawHref);
        String target = switch (resolution.status()) {
            case RESOLVED -> resolution.targetUrl();
            case UNRESOLVED -> "";
            case NOT_A_REDIRECT -> isNavigable(rawHref) ? rawHref : "";
        };
        // Wrapped engine links (Bing /ck/, DuckDuckGo /l/) count by their target, not by the engine host.
        DomainNames.DomainRelation relation = target.isEmpty()
                ? DomainNames.DomainRelation.ACTION : DomainNames.classify(target, pageUrl);
        if (context.links >= settings.maximumLinksPerContainer()) {
            truncated = true;
            return relation;
        }
        context.links++;
        Element enclosingHeading = anchor.closest("h1, h2, h3, h4, h5, h6");
        // A heading INSIDE the anchor (Google's <a><h3>Title</h3><cite>…</cite></a>) is the title.
        Element innerHeading = enclosingHeading == null ? anchor.selectFirst("h1, h2, h3, h4, h5, h6") : null;
        String text = innerHeading != null ? textOf(innerHeading, LINK_TEXT) : textOf(anchor, LINK_TEXT);
        Element heading = enclosingHeading != null ? enclosingHeading : innerHeading;
        links.add(new SerpLink("link-" + String.format(Locale.ROOT, "%04d", ++linkSeq), containerId,
                rawHref, target, resolution.status(), text, textOf(context.containerElement, EXCERPT),
                heading != null ? textOf(heading, 200) : context.lastHeading, displayedDomainOf(anchor),
                relation, !hidden && !text.isEmpty(), heading != null));
        return relation;
    }

    private static void countLink(DomainNames.DomainRelation relation, Stats stats) {
        stats.linkCount++;
        switch (relation) {
            case ACTION -> stats.action++;
            case SAME_HOST -> stats.sameHost++;
            case SUBDOMAIN -> stats.subdomain++;
            case SAME_REGISTRABLE_DOMAIN -> stats.sameRegistrable++;
            case EXTERNAL -> stats.external++;
        }
    }

    private static boolean isNavigable(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String displayedDomainOf(Element anchor) {
        Element current = anchor;
        for (int hops = 0; current != null && hops < 4; hops++, current = current.parent()) {
            Element cite = current.selectFirst("cite");
            if (cite != null) return truncate(clean(cite.text()), 120);
        }
        return "";
    }

    private List<String> adapterBlocks(Document document, String selector) {
        if (selector == null || selector.isBlank()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (Element match : document.select(selector)) {
                Element current = match;
                while (current != null && !containerIds.containsKey(current)) current = current.parent();
                if (current != null) ids.add(containerIds.get(current));
            }
        } catch (Selector.SelectorParseException invalid) {
            return List.of();
        }
        return List.copyOf(ids);
    }

    private static boolean isContainer(Element element) {
        return CONTAINER.contains(element.normalName());
    }

    static boolean isHidden(Element element) {
        if (element.hasAttr("hidden") || "true".equalsIgnoreCase(element.attr("aria-hidden"))) return true;
        if (element.normalName().equals("input") && "hidden".equalsIgnoreCase(element.attr("type"))) return true;
        String style = element.attr("style").toLowerCase(Locale.ROOT).replace(" ", "");
        return style.contains("display:none") || style.contains("visibility:hidden");
    }

    private static List<String> classTokens(Element element) {
        List<String> tokens = new ArrayList<>(element.classNames());
        return tokens.size() > 12 ? tokens.subList(0, 12) : tokens;
    }

    private static List<String> semanticFlags(Element element, String role) {
        String tag = element.normalName();
        List<String> flags = new ArrayList<>();
        if (tag.equals("main") || role.equals("main")) flags.add("MAIN");
        if (tag.equals("nav") || role.equals("navigation")) flags.add("NAV");
        if (tag.equals("header") || role.equals("banner")) flags.add("HEADER");
        if (tag.equals("footer") || role.equals("contentinfo")) flags.add("FOOTER");
        if (tag.equals("aside") || role.equals("complementary")) flags.add("ASIDE");
        if (tag.equals("article")) flags.add("ARTICLE");
        if (tag.equals("section")) flags.add("SECTION");
        if (tag.equals("form") || role.equals("search")) flags.add("FORM");
        if (tag.equals("ul") || tag.equals("ol")) flags.add("LIST");
        return flags;
    }

    /** Simplified block shape, e.g. {@code div(h2(a),div(a,p))}, bounded in depth and fan-out. */
    static String signature(Element element, int depth) {
        String tag = element.normalName();
        if (depth <= 0 || element.childrenSize() == 0) return tag;
        List<String> kids = new ArrayList<>();
        for (Element child : element.children()) {
            if (SKIP.contains(child.normalName())) continue;
            kids.add(signature(child, depth - 1));
            if (kids.size() >= 8) break;
        }
        return kids.isEmpty() ? tag : tag + "(" + String.join(",", kids) + ")";
    }

    /** Visible text without script/style/hidden subtrees, whitespace-normalised and bounded. */
    static String textOf(Element element, int limit) {
        StringBuilder out = new StringBuilder();
        appendText(element, out, limit);
        return truncate(clean(out.toString()), limit);
    }

    private static void appendText(Node node, StringBuilder out, int limit) {
        if (out.length() > (long) limit + 64) return;
        if (node instanceof TextNode text) {
            out.append(text.getWholeText()).append(' ');
            return;
        }
        if (node instanceof Element element) {
            if (SKIP.contains(element.normalName()) || isHidden(element)) return;
            for (Node child : element.childNodes()) appendText(child, out, limit);
        }
    }

    private static List<SerpContainer> withSimilarSiblingCounts(List<SerpContainer> containers) {
        Map<String, Map<String, Integer>> signaturesByParent = new HashMap<>();
        for (SerpContainer container : containers) {
            signaturesByParent.computeIfAbsent(container.parentId(), ignored -> new HashMap<>())
                    .merge(container.structureSignature(), 1, Integer::sum);
        }
        List<SerpContainer> result = new ArrayList<>(containers.size());
        for (SerpContainer c : containers) {
            int similar = signaturesByParent.get(c.parentId()).get(c.structureSignature()) - 1;
            result.add(new SerpContainer(c.id(), c.parentId(), c.childIds(), c.siblingIndex(), c.depth(),
                    c.tag(), c.elementId(), c.classTokens(), c.role(), c.semanticFlags(), c.textExcerpt(),
                    c.textLength(), c.linkTextLength(), c.headingCount(), c.paragraphCount(), c.linkCount(),
                    c.sameHostLinks(), c.subdomainLinks(), c.sameRegistrableLinks(), c.externalLinks(),
                    c.actionLinks(), c.visible(), c.structureSignature(), similar));
        }
        return result;
    }

    static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit).trim() : text;
    }
}
