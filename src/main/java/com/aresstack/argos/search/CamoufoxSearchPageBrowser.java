package com.aresstack.argos.search;

import com.aresstack.argos.CamoufoxBrowser;

/** Adapter from the search's browser port to Argos' single Camoufox session. */
final class CamoufoxSearchPageBrowser implements SearchPageBrowser {
    private final CamoufoxBrowser browser;

    CamoufoxSearchPageBrowser(CamoufoxBrowser browser) {
        this.browser = browser;
    }

    @Override
    public LoadedPage open(String url) {
        return toLoadedPage(browser.loadHtml(url));
    }

    @Override
    public LoadedPage current() {
        return toLoadedPage(browser.currentHtml());
    }

    @Override
    public boolean click(String cssSelector) {
        return browser.clickWithTrustedInput(cssSelector);
    }

    private static LoadedPage toLoadedPage(CamoufoxBrowser.HtmlPage page) {
        return new LoadedPage(page.url(), page.title(), page.status(), page.html());
    }
}
