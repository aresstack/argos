package com.aresstack.argos.research;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns serialized page HTML into a {@link ResearchPage} inside the JVM (jsoup), so reading a page needs
 * no page-visible script. Page chrome (navigation, header, footer, forms) is left out of the text but its
 * links stay available.
 */
public final class HtmlResearchPages {
    private static final int MAX_LINKS = 300;
    /** A real article that discusses CAPTCHAs is long; an interstitial is short. */
    private static final int CHALLENGE_TEXT_LIMIT = 1_500;
    private static final Set<Integer> BLOCKING_STATUS = Set.of(401, 403, 407, 429, 451);
    private static final List<String> CHALLENGE_URL_MARKERS = List.of(
            "/captcha", "/sorry/", "challenges.cloudflare.com", "/cdn-cgi/challenge");
    private static final List<String> CHALLENGE_TEXTS = List.of(
            "verify you are human", "checking your browser", "unusual traffic", "i'm not a robot",
            "ich bin kein roboter", "bestätigen sie, dass sie ein mensch sind", "one last step",
            "enable javascript and cookies to continue", "ungewöhnlichen datenverkehr");

    private HtmlResearchPages() {
    }

    public static ResearchPage parse(String url, String title, int status, String html) {
        Document document = Jsoup.parse(html == null ? "" : html, url == null ? "" : url);
        String pageTitle = title == null || title.isBlank() ? document.title() : title;
        String text = readableText(document);
        return new ResearchPage(url, pageTitle.trim(), status, text, links(document),
                access(url, status, text));
    }

    private static String readableText(Document document) {
        Element root = document.body() == null ? document : document.body();
        Element content = root.clone();
        content.select("script, style, noscript, template, svg, iframe, [hidden], [aria-hidden=true]").remove();
        String full = content.text();
        content.select("nav, header, footer, aside, form, [role=navigation], [role=banner], [role=contentinfo]")
                .remove();
        String main = content.text();
        // Sites that wrap everything in a <header> or <form> still have something to say.
        return main.length() >= 200 || main.length() * 4 >= full.length() ? main : full;
    }

    private static List<ResearchPage.PageLink> links(Document document) {
        Map<String, ResearchPage.PageLink> links = new LinkedHashMap<>();
        for (Element anchor : document.select("a[href]")) {
            String target = anchor.absUrl("href");
            int fragment = target.indexOf('#');
            if (fragment >= 0) target = target.substring(0, fragment);
            if (!ResearchUrls.isHttp(target) || links.containsKey(target)) continue;
            String text = anchor.text().trim();
            if (text.isEmpty()) text = anchor.attr("title").trim();
            if (text.isEmpty()) text = anchor.attr("aria-label").trim();
            links.put(target, new ResearchPage.PageLink(text, target));
            if (links.size() >= MAX_LINKS) break;
        }
        return new ArrayList<>(links.values());
    }

    private static ResearchPage.Access access(String url, int status, String text) {
        if (BLOCKING_STATUS.contains(status)) return ResearchPage.Access.BLOCKED;
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        for (String marker : CHALLENGE_URL_MARKERS) {
            if (lowerUrl.contains(marker)) return ResearchPage.Access.CHALLENGE;
        }
        if (text.length() < CHALLENGE_TEXT_LIMIT) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String marker : CHALLENGE_TEXTS) {
                if (lower.contains(marker)) return ResearchPage.Access.CHALLENGE;
            }
        }
        return ResearchPage.Access.READABLE;
    }
}
