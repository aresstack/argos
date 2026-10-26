package com.aresstack.argos.research;

import com.aresstack.argos.CamoufoxBrowser;

/**
 * Adapter from the research to Argos' Camoufox session: navigation plus {@code page.content()}, read in
 * Playwright's isolated world. Nothing is evaluated in the page and nothing is written to it.
 */
public final class CamoufoxResearchPageReader implements ResearchPageReader {
    private final CamoufoxBrowser browser;

    public CamoufoxResearchPageReader(CamoufoxBrowser browser) {
        this.browser = browser;
    }

    @Override
    public ResearchPage read(String url) {
        CamoufoxBrowser.HtmlPage page = browser.loadHtml(url);
        return HtmlResearchPages.parse(page.url(), page.title(), page.status(), page.html());
    }
}
