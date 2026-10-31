package com.aresstack.argos.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.Optional;

/**
 * How Argos asks an engine, the same way for every tool profile: open the engine's regular start page, find its
 * search field through the endpoint's locators, type the query with real key input and press a real Enter
 * (WebDriver BiDi through {@link SearchPageBrowser#submitQuery}). What comes back is the page the engine loaded in
 * answer, for the unchanged extraction. No result URL is ever built, and there is no direct-URL fallback: a start
 * page whose field cannot be operated ends the attempt as {@link SearchBoxUnavailableException}.
 *
 * <p>A start page may hide its field behind a consent banner; the banner is resolved like on a result page (one
 * trusted click, never "accept all" without opt-in) and the field tried once more. A challenge or an
 * accept-only consent wall on the start page is handed back as the page, so the caller reports it as such.
 */
final class SearchFormInteraction {
    /** The start page was reached, but its search field could not be found or operated. */
    static final class SearchBoxUnavailableException extends Exception {
        SearchBoxUnavailableException(String message) {
            super(message);
        }
    }

    private SearchFormInteraction() {
    }

    static SearchPageBrowser.LoadedPage search(SearchPageBrowser browser, SearchEngine.Endpoint endpoint, String query,
                                               boolean acceptAllConsent) throws Exception {
        String typed = typedForm(query);
        SearchPageBrowser.LoadedPage home = browser.open(endpoint.homeUrl());
        String problem;
        try {
            if (browser.submitQuery(endpoint.searchBoxSelectors(), typed)) return browser.current();
            problem = "no search field matches " + endpoint.searchBoxSelectors();
        } catch (Exception refused) {
            problem = "search field not usable: " + WebSearchService.safeMessage(refused);
        }
        Document html = parse(home);
        Optional<SearchPageGuards.ConsentControl> consent = SearchPageGuards.findConsentControl(html, acceptAllConsent);
        if (consent.isPresent()) {
            try {
                if (browser.click(consent.get().selector())) {
                    home = browser.current();
                    html = parse(home);
                    if (browser.submitQuery(endpoint.searchBoxSelectors(), typed)) return browser.current();
                }
            } catch (Exception stillBlocked) {
                problem = "search field not usable after the consent banner: " + WebSearchService.safeMessage(stillBlocked);
            }
        }
        if (SearchPageGuards.findChallenge(html, home.url(), html.body() == null ? "" : html.body().text()).isPresent()
                || SearchPageGuards.offersOnlyAcceptance(html)) {
            return home; // the caller recognizes the wall and reports it
        }
        throw new SearchBoxUnavailableException(problem);
    }

    /** One line of text as a person types it: inner line breaks and tabs would act as keys, not as text. */
    static String typedForm(String query) {
        return query.strip().replaceAll("\\s+", " ");
    }

    private static Document parse(SearchPageBrowser.LoadedPage page) {
        return Jsoup.parse(page.html() == null ? "" : page.html(), page.url() == null ? "" : page.url());
    }
}
