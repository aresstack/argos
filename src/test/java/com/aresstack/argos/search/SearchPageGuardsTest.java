package com.aresstack.argos.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchPageGuardsTest {
    @Test
    void consentPrefersRejectAndYieldsASelectorForTheTrustedClick() {
        Document page = Jsoup.parse(SerpFixtures.html("consent-google.html"), "https://consent.google.com/ml");

        var control = SearchPageGuards.findConsentControl(page, false).orElseThrow();

        assertEquals(SearchPageGuards.ConsentAction.REJECT_ALL, control.action());
        assertEquals("Alle ablehnen", page.selectFirst(control.selector()).text());
    }

    @Test
    void resultPagesOfferNoConsentControlAndNoChallenge() {
        Document page = Jsoup.parse(SerpFixtures.html("duckduckgo-html.html"), "https://html.duckduckgo.com/html/");

        assertTrue(SearchPageGuards.findConsentControl(page, false).isEmpty());
        assertTrue(SearchPageGuards.findChallenge(page, page.location(), page.text()).isEmpty());
    }

    @Test
    void recognisesBingChallengeAndGoogleSorryInterstitial() {
        Document bing = Jsoup.parse(SerpFixtures.html("captcha-bing.html"), "https://www.bing.com/search?q=x");
        assertTrue(SearchPageGuards.findChallenge(bing, bing.location(), bing.text()).isPresent());

        Document sorry = Jsoup.parse("<html><body><p>Our systems have detected unusual traffic</p></body></html>");
        var challenge = SearchPageGuards.findChallenge(sorry, "https://www.google.com/sorry/index?continue=x", "");
        assertEquals("url:www.google.com", challenge.orElseThrow().evidence());
    }

    @Test
    void acceptAllIsNeverChosenWithoutOptIn() {
        Document page = Jsoup.parse(SerpFixtures.html("consent-accept-only.html"), "https://www.google.com/");

        assertTrue(SearchPageGuards.findConsentControl(page, false).isEmpty());
        assertTrue(SearchPageGuards.offersOnlyAcceptance(page));
        assertEquals(SearchPageGuards.ConsentAction.ACCEPT_ALL,
                SearchPageGuards.findConsentControl(page, true).orElseThrow().action());
    }

    @Test
    void hiddenConsentControlsAreIgnored() {
        Document page = Jsoup.parse("<div style='display:none' class='cookie'>"
                + "<button id='onetrust-reject-all-handler'>Reject all</button></div>");

        assertTrue(SearchPageGuards.findConsentControl(page, false).isEmpty());
    }
}
