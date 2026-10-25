package com.aresstack.argos.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlResearchPagesTest {
    @Test
    void readsArticleTextWithoutPageChromeAndKeepsAbsoluteLinks() {
        String html = "<html><head><title>T</title><script>var secret=1</script></head><body>"
                + "<nav><a href='/home'>Home</a></nav><main><h1>Camoufox</h1>"
                + "<p>" + "Camoufox is a Firefox build. ".repeat(10) + "</p>"
                + "<a href='docs/rotation#part'>Fingerprint rotation</a><a href='docs/rotation'>again</a>"
                + "<a href='mailto:x@example.org'>Mail</a><a href='https://other.example/x' title='Other'></a>"
                + "</main><footer>Footer text</footer></body></html>";

        ResearchPage page = HtmlResearchPages.parse("https://a.example/base/", "", 200, html);

        assertEquals("T", page.title());
        assertTrue(page.text().startsWith("Camoufox Camoufox is a Firefox build."));
        assertFalse(page.text().contains("secret"));
        assertFalse(page.text().contains("Footer text"));
        assertEquals(List.of(
                new ResearchPage.PageLink("Home", "https://a.example/home"),
                new ResearchPage.PageLink("Fingerprint rotation", "https://a.example/base/docs/rotation"),
                new ResearchPage.PageLink("Other", "https://other.example/x")), page.links());
        assertEquals(ResearchPage.Access.READABLE, page.access());
    }

    @Test
    void keepsTheTextOfPagesThatWrapEverythingInChrome() {
        ResearchPage page = HtmlResearchPages.parse("https://a.example/", "T", 200,
                "<body><form><p>Only content lives inside the form.</p></form></body>");
        assertEquals("Only content lives inside the form.", page.text());
    }

    @Test
    void readsXmlWithoutAnHtmlBody() {
        ResearchPage page = HtmlResearchPages.parse("https://a.example/feed.xml", "", 200,
                "<?xml version='1.0'?><rss><channel><item><title>Camoufox release</title></item></channel></rss>");
        assertTrue(page.text().contains("Camoufox release"));
    }

    @Test
    void classifiesBlocksAndChallengesButNotArticlesAboutCaptchas() {
        assertEquals(ResearchPage.Access.BLOCKED,
                HtmlResearchPages.parse("https://a.example/", "x", 403, "<p>Forbidden</p>").access());
        assertEquals(ResearchPage.Access.CHALLENGE,
                HtmlResearchPages.parse("https://a.example/", "x", 200, "<p>Verify you are human</p>").access());
        assertEquals(ResearchPage.Access.CHALLENGE,
                HtmlResearchPages.parse("https://www.google.com/sorry/index", "x", 200, "<p>x</p>").access());
        String article = "<p>" + "How sites ask you to verify you are human with a CAPTCHA. ".repeat(40) + "</p>";
        assertEquals(ResearchPage.Access.READABLE,
                HtmlResearchPages.parse("https://a.example/", "x", 200, article).access());
    }
}
