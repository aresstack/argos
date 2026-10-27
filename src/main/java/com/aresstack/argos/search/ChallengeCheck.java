package com.aresstack.argos.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** The search's challenge recognition ({@link SearchPageGuards}) for any page the browser read. */
public final class ChallengeCheck {
    private ChallengeCheck() {
    }

    /** True when the serialized DOM shows an access challenge (CAPTCHA, bot wall); markup only, no page script. */
    public static boolean isChallenge(String html, String url, String visibleText) {
        if (html == null || html.isBlank()) return false;
        Document document = Jsoup.parse(html, url == null ? "" : url);
        return SearchPageGuards.findChallenge(document, url, visibleText == null ? "" : visibleText).isPresent();
    }
}
