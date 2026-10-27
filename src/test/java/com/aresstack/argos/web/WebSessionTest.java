package com.aresstack.argos.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class WebSessionTest {
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private HttpServer site;
    private String base;
    private HttpPageBrowser browser;
    private WebSession web;

    @BeforeEach void startSite() throws Exception {
        site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/moved")) {
                exchange.getResponseHeaders().add("Location", "/article");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (path.equals("/broken") || path.equals("/broken-alternative")) {
                byte[] body = "<title>500 Internal Server Error</title><h1>Internal Server Error</h1><p>Try again later.</p>"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(500, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
                return;
            }
            byte[] body = pages.getOrDefault(path, "<title>Missing</title><p>Not found</p>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(pages.containsKey(path) ? 200 : 404, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
        pages.put("/", "<title>Start</title><h1>Welcome</h1>"
                + "<a href='/article'>Read the article</a> <a href='/about'>About us</a>"
                + " <a href='mailto:x@y.z'>Mail</a>");
        pages.put("/article", "<title>Article</title><p>Camoufox keeps its fingerprint.</p>"
                + "<a href='/'>Home</a>");
        pages.put("/about", "<title>About</title><p>About page</p>");
        browser = new HttpPageBrowser();
        web = new WebSession(browser, UrlPolicy.off(), null, 40, 50);
    }

    @AfterEach void stopSite() {
        site.stop(0);
    }

    @Test void opensAPageAndReportsTitleTextAndLinkCount() {
        String answer = web.open(base + "/");
        assertTrue(answer.startsWith("URL: " + base + "/\nTITLE: Start\nLINKS: 2"), answer);
        assertTrue(answer.contains("Welcome"));
        assertFalse(answer.contains("HTTP error:"), "a delivered page carries no error line: " + answer);
    }

    @Test void anErrorPageIsMarkedWithItsHttpStatus() {
        String answer = web.open(base + "/broken");
        assertTrue(answer.startsWith("URL: " + base + "/broken\nTITLE: 500 Internal Server Error\n"
                + "HTTP error: 500 Internal Server Error \u2014 the requested content may not be available on this page.\n"
                + "NEXT ACTION: Do not retry this failed URL immediately. Call web_search with a query for the same information from an alternative source.\n"
                + "LINKS: 0"), answer);
        assertTrue(answer.contains("Try again later."), "the server's message is still shown: " + answer);
        assertTrue(web.read(1).contains("\nHTTP error: 500 Internal Server Error \u2014"), "web_read keeps the status");
        assertFalse(web.open(base + "/about").contains("HTTP error:"), "a delivered page carries no error line");
        // The double fetches again on back(); Camoufox reloads the document too (bfcache off), so the status is known.
        assertTrue(web.back().contains("\nHTTP error: 500 Internal Server Error \u2014"), "going back marks it again");
        pages.put("/", "<title>Start</title><a href='/missing'>Gone</a> <a href='/about'>About us</a>");
        web.open(base + "/");
        assertTrue(web.follow("link-1").contains("TITLE: Missing\nHTTP error: 404 Not Found \u2014 the requested"),
                "a followed 404 is marked");
        assertFalse(web.back().contains("HTTP error:"), "the start page carries no error line");
        assertTrue(web.follow("link-2").startsWith("URL: " + base + "/about\nTITLE: About\nLINKS: 0"));
        assertEquals("HTTP error: 599 \u2014 the requested content may not be available on this page.",
                WebSession.errorLine(599), "an unknown code is shown without a reason phrase");
    }

    @Test void anHttp500ProvidesTheNextUntriedSearchResultAsAnExplicitAction() throws Exception {
        WebSearchPort engine = query -> new SearchResults(List.of(
                new SearchHit("Broken official", base + "/broken", ""),
                new SearchHit("Broken mirror", base + "/broken-alternative", ""),
                new SearchHit("Working mirror", base + "/article", "")), List.of());
        WebSession searching = new WebSession(browser, UrlPolicy.off(), engine);
        searching.search("observatory dark-sky zone");

        String firstFailure = searching.follow("result-1");
        assertTrue(firstFailure.contains("NEXT ACTION: Do not retry this failed URL. Open the next untried search result now: result-2. Call web_follow link_id=\"result-2\"."),
                firstFailure);

        String secondFailure = searching.follow("result-2");
        assertTrue(secondFailure.contains("NEXT ACTION: Do not retry this failed URL. Open the next untried search result now: result-3. Call web_follow link_id=\"result-3\"."),
                secondFailure);
        assertTrue(searching.follow("result-3").contains("TITLE: Article"));
    }

    @Test void anHttp500SuggestsANewSearchWhenAllResultsHaveBeenTried() throws Exception {
        WebSession searching = new WebSession(browser, UrlPolicy.off(), query -> new SearchResults(
                List.of(new SearchHit("Broken official", base + "/broken", "")), List.of()));
        searching.search("observatory dark-sky zone");

        String failure = searching.follow("result-1");

        assertTrue(failure.contains("NEXT ACTION: No untried result remains from the last search. Call web_search query=\"observatory dark-sky zone alternative source\""),
                failure);
        assertFalse(failure.contains("Call web_follow link_id=\"result-1\""), failure);
    }
    @Test void aCaptureWithoutAKnownStatusRendersAsBefore() {
        // Status 0: no response recorded for the shown address, or a port that does not know the status.
        BrowserPagePort unknown = new BrowserPagePort() {
            @Override public PageCapture open(String url, String referer) {
                return new PageCapture("http://h.example/", "Start", "Welcome", "");
            }
            @Override public PageCapture current() { return open(null, null); }
            @Override public String currentUrl() { return "http://h.example/"; }
            @Override public PageCapture back() { return null; }
            @Override public void clear() { }
        };
        assertEquals("URL: http://h.example/\nTITLE: Start\nLINKS: 0 (see web_links)\n\nWelcome",
                new WebSession(unknown, UrlPolicy.off(), null).open("http://h.example/"));
        assertFalse(new PageCapture("u", "t", "x", "", 399).isError(), "399 is not an error");
        assertTrue(new PageCapture("u", "t", "x", "", 400).isError(), "400 is");
    }

    @Test void listsLinksWithShortIdsAndFollowsThem() {
        web.open(base + "/");
        assertEquals("link-1: Read the article — " + base + "/article\n"
                + "link-2: About us — " + base + "/about", web.links());
        assertTrue(web.follow("link-1").contains("TITLE: Article"));
        assertEquals(base + "/", browser.lastReferer, "a followed link carries its referer");
    }

    @Test void acceptsSloppyLinkIdsFromSmallModels() {
        web.open(base + "/");
        assertTrue(web.follow("2").contains("TITLE: About"));
        web.back();
        assertTrue(web.follow(" Link-2 ").contains("TITLE: About"));
        web.back();
        assertTrue(web.follow("[2]").contains("TITLE: About"));
    }

    @Test void keepsLinkIdsStableWhenThePageLoadsMoreLinks() {
        web.open(base + "/");
        pages.put("/", "<title>Start</title><a href='/new'>New item</a>"
                + "<a href='/about'>About us</a><a href='/article'>Read the article</a>");
        assertFalse(web.links().contains("New item"), "web_links itself does not read the page again");
        web.read(1);
        String links = web.links();
        assertTrue(links.contains("link-1: Read the article"), links);
        assertTrue(links.contains("link-2: About us"), links);
        assertTrue(links.contains("link-3: New item"), links);
    }

    @Test void linkIdsOfAnotherPageAreRejected() {
        web.open(base + "/about");
        var error = assertThrows(IllegalArgumentException.class, () -> web.follow("link-1"));
        assertTrue(error.getMessage().contains("call web_links first"));
    }

    @Test void goesBackToThePreviousPage() {
        web.open(base + "/");
        web.follow("link-1");
        assertTrue(web.back().contains("TITLE: Start"));
        web.open(base + "/about");
        assertTrue(web.back().contains("TITLE: Start"));
    }

    @Test void longPagesAreReadInParts() {
        pages.put("/long", "<title>Long</title><p>" + "abcdefghij".repeat(9) + "</p>");
        String first = web.open(base + "/long");
        assertTrue(first.contains("PART: 1 of 3 (next: web_read part=2)"), first);
        String last = web.read(3);
        assertTrue(last.contains("PART: 3 of 3\n"), last);
        assertTrue(last.endsWith("abcdefghij"), last);
        assertThrows(IllegalArgumentException.class, () -> web.read(4));
    }

    @Test void findsTextOnALongPageWithSnippetsAndParts() {
        pages.put("/archive", "<title>Archive</title><p>" + "filler text here. ".repeat(5) + "</p>"
                + "<p>Its founding Director was Margit Solberg, who led it until 1988.</p>");
        web.open(base + "/archive");
        String answer = web.find("founding  director");
        assertTrue(answer.startsWith("URL: " + base + "/archive\nFOUND: 1 match for \"founding  director\"\n"), answer);
        assertTrue(answer.contains("hit 1 (part 3): "), answer);
        assertTrue(answer.contains("Its founding Director was Margit Solberg"), answer);
        assertTrue(answer.endsWith("Read a whole part with web_read part=N."), answer);
        assertTrue(web.read(3).contains("founding Director"), "the named part holds the match");
    }

    @Test void findCountsAllMatchesButShowsAtMostTenShortSnippets() {
        web = new WebSession(browser, UrlPolicy.off(), null);
        pages.put("/many", "<title>Many</title>" + "<p>An apple a day and more words around it.</p>".repeat(30)
                + "<p>" + "x".repeat(600) + " apple " + "y".repeat(600) + "</p>");
        web.open(base + "/many");
        String answer = web.find("APPLE");
        assertTrue(answer.contains("FOUND: 31 matches for \"APPLE\" (showing 10)"), answer);
        long hits = answer.lines().filter(line -> line.startsWith("hit ")).count();
        assertEquals(10, hits, answer);
        answer.lines().filter(line -> line.startsWith("hit ")).forEach(line ->
                assertTrue(line.length() <= "hit 10 (part 1): ".length() + 2 + WebSession.FIND_SNIPPET_CHARACTERS, line));
        assertFalse(answer.contains("web_read part=N"), "a one-part page needs no hint");
    }

    @Test void aPhraseLongerThanTheSnippetLimitStillGivesAShortSnippet() {
        String sentence = "The observatory dome was rebuilt after the storm of 1974 with a lighter frame and new shutters. ";
        String phrase = sentence.repeat(4).strip();                          // ~390 characters
        pages.put("/longphrase", "<title>Dome</title><p>Intro words before. " + phrase + " Words after.</p>");
        web.open(base + "/longphrase");
        String answer = web.find(phrase);
        assertTrue(answer.contains("FOUND: 1 match for \"" + phrase.substring(0, WebSession.MAX_ECHOED_PHRASE) + "…\""),
                answer);
        String hit = answer.lines().filter(line -> line.startsWith("hit 1 (part ")).findFirst().orElseThrow();
        String snippet = hit.substring(hit.indexOf("): ") + 3);
        assertTrue(snippet.startsWith("…The observatory dome"), "shown from the start of the match: " + snippet);
        assertTrue(snippet.endsWith("…"), snippet);
        String text = snippet.substring(1, snippet.length() - 1);
        assertTrue(text.length() <= WebSession.FIND_SNIPPET_CHARACTERS, text.length() + ": " + text);
        assertTrue(phrase.startsWith(text), "cut at a word boundary inside the match: " + text);
    }

    @Test void findIgnoresDashVariantsAndReportsMisses() {
        pages.put("/dash", "<title>Dash</title><p>The state\u2011of\u2011the\u2011art telescope.</p>");
        web.open(base + "/dash");
        assertTrue(web.find("state-of-the-art").contains("FOUND: 1 match"));
        assertTrue(web.find("planetarium").contains("No matches for \"planetarium\" on this page."));
        assertThrows(IllegalArgumentException.class, () -> web.find(" "));
    }

    @Test void findNeedsAnOpenPageAndDoesNotNavigate() {
        var error = assertThrows(IllegalStateException.class, () -> web.find("x"));
        assertTrue(error.getMessage().startsWith("No page is open yet"), error.getMessage());
        web.open(base + "/");
        web.find("Welcome");
        assertEquals(base + "/", browser.currentUrl());
        assertEquals("link-1: Read the article — " + base + "/article\n"
                + "link-2: About us — " + base + "/about", web.links(), "link ids stay valid");
    }

    @Test void reportsTheFinalUrlAfterRedirects() {
        assertTrue(web.open(base + "/moved").startsWith("URL: " + base + "/article"));
    }

    @Test void requiresAPageBeforeReading() {
        var error = assertThrows(IllegalStateException.class, () -> web.read(1));
        assertTrue(error.getMessage().startsWith("No page is open yet"), error.getMessage());
    }

    @Test void readsThePageAnotherToolOpened() {
        browser.moveTo(base + "/article");                 // browser_navigate in the expert profile
        String answer = web.read(1);
        assertTrue(answer.startsWith("URL: " + base + "/article\nTITLE: Article\nLINKS: 1"), answer);
        assertEquals("link-1: Home — " + base + "/", web.links());
        assertTrue(web.follow("link-1").contains("TITLE: Start"));
        assertEquals(base + "/article", browser.lastReferer, "the adopted page is the referer");
    }

    @Test void linkIdsFollowTheBrowserWhenAnotherToolMovedIt() {
        web.open(base + "/");
        browser.moveTo(base + "/article");
        assertEquals("link-1: Home — " + base + "/", web.links(), "ids belong to the page the browser shows");
        browser.clear();                                   // a blank tab is nothing to read
        var error = assertThrows(IllegalStateException.class, web::links);
        assertTrue(error.getMessage().startsWith("No page is open yet"), error.getMessage());
    }

    @Test void aResultsPageThatRewritesItsQueryLaterIsStillTheProvidersPage() throws Exception {
        WebSearchPort engine = query -> {
            browser.moveTo(base + "/about?q=camoufox");
            return new SearchResults(List.of(new SearchHit("Article", base + "/article", "")), List.of());
        };
        WebSession searching = new WebSession(browser, UrlPolicy.off(), engine);
        searching.search("camoufox");
        browser.show(base + "/about?q=camoufox&ia=web#top");    // history.replaceState by the engine's script
        var error = assertThrows(IllegalStateException.class, () -> searching.read(1));
        assertTrue(error.getMessage().contains("web_follow link_id=\"result-1\""), error.getMessage());
        browser.moveTo(base + "/");                               // another path on the same host is a page
        assertTrue(searching.read(1).contains("TITLE: Start"));
    }

    @Test void aSearchWithoutResultsStillLeavesTheProvidersPageAlone() throws Exception {
        WebSearchPort engine = query -> {
            browser.moveTo(base + "/about");
            return new SearchResults(List.of(), List.of("ATTEMPT: local NO_ORGANIC_RESULTS"));
        };
        WebSession searching = new WebSession(browser, UrlPolicy.off(), engine);
        assertTrue(searching.search("nothing").startsWith("No results."));
        int reads = browser.reads;
        var error = assertThrows(IllegalStateException.class, () -> searching.read(1));
        assertTrue(error.getMessage().startsWith("No page is open yet"), error.getMessage());
        assertEquals(reads, browser.reads, "the provider's page is not read");
        browser.moveTo(base + "/article");
        assertTrue(searching.read(1).contains("TITLE: Article"));
    }

    @Test void aFailedSearchOnTheProvidersPageIsNotAdoptedEither() {
        web.open(base + "/");
        WebSession stuck = new WebSession(browser, UrlPolicy.off(), query -> {
            throw new IllegalStateException("No search engine answered.");
        });
        assertThrows(IllegalStateException.class, () -> stuck.search("x"));
        WebSession walled = new WebSession(browser, UrlPolicy.off(), query -> {
            browser.moveTo(base + "/about");                   // consent wall on the provider's page
            throw new IllegalStateException("Consent wall.");
        });
        walled.open(base + "/");
        assertThrows(IllegalStateException.class, () -> walled.search("x"));
        var error = assertThrows(IllegalStateException.class, () -> walled.read(1));
        assertTrue(error.getMessage().startsWith("No page is open yet"), error.getMessage());
    }

    @Test void aFailedSearchThatDidNotMoveTheBrowserKeepsThePage() {
        WebSession stuck = new WebSession(browser, UrlPolicy.off(), query -> {
            throw new IllegalStateException("No search engine answered.");
        });
        stuck.open(base + "/");
        assertThrows(IllegalStateException.class, () -> stuck.search("x"));
        assertTrue(stuck.read(1).contains("TITLE: Start"), "the browser never left the page");
    }

    @Test void nonWebDocumentsAreReportedWithoutTouchingTheTab() {
        web.open(base + "/");
        browser.show("blob:" + base + "/3f2a");                 // a preview the page opened in the same tab
        var error = assertThrows(IllegalStateException.class, () -> web.read(1));
        assertTrue(error.getMessage().startsWith("The browser shows no http(s) page (blob:"), error.getMessage());
        assertEquals("blob:" + base + "/3f2a", browser.currentUrl(), "web_* must not clear a tab another tool built");
        assertThrows(IllegalStateException.class, web::links);
    }

    @Test void aFragmentMoveDoesNotMakeLinksReadThePageAgain() {
        web.open(base + "/");
        browser.show(base + "/#part-2");
        int reads = browser.reads;
        assertTrue(web.links().startsWith("link-1: Read the article"));
        assertEquals(reads, browser.reads, "same document, same links");
    }

    @Test void theSearchProvidersPageIsNotAdoptedButALaterNavigationIs() throws Exception {
        WebSearchPort engine = query -> {
            browser.moveTo(base + "/about");               // the provider leaves the browser on its results page
            return new SearchResults(List.of(new SearchHit("Article", base + "/article", "")), List.of());
        };
        WebSession searching = new WebSession(browser, UrlPolicy.off(), engine);
        searching.search("camoufox");
        var error = assertThrows(IllegalStateException.class, () -> searching.read(1));
        assertTrue(error.getMessage().contains("web_follow link_id=\"result-1\""), error.getMessage());
        browser.moveTo(base + "/article");                 // browser_navigate after the search
        assertTrue(searching.read(1).contains("TITLE: Article"));
        assertTrue(searching.follow("result-1").contains("TITLE: Article"), "results stay valid");
    }

    @Test void strictPolicyBlocksLoopbackAndNonWebUrls() {
        WebSession strict = new WebSession(browser, UrlPolicy.publicOnly(), null);
        assertThrows(IllegalArgumentException.class, () -> strict.open(base + "/"));
        assertThrows(IllegalArgumentException.class, () -> strict.open("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> strict.open("javascript:alert(1)"));
        for (String internal : List.of("http://[fd00::1]/admin", "http://[fc00::2]/", "http://[::1]/",
                "http://[fe80::1]/", "http://10.0.0.1/", "http://192.168.1.1/", "http://100.64.0.1/",
                "http://[::ffff:127.0.0.1]/")) {
            assertThrows(IllegalArgumentException.class, () -> strict.open(internal), internal);
        }
        assertDoesNotThrow(() -> UrlPolicy.publicOnly().check("http://[2606:4700::1111]/"));
    }

    @Test void strictPolicyAbandonsPagesThatRedirectIntoPrivateNetworks() {
        boolean[] cleared = {false};
        BrowserPagePort redirecting = new BrowserPagePort() {
            @Override public PageCapture open(String url, String referer) {
                return new PageCapture("http://127.0.0.1/admin", "Router", "secret", "");
            }
            @Override public PageCapture current() { return open(null, null); }
            @Override public String currentUrl() { return cleared[0] ? "about:blank" : "http://127.0.0.1/admin"; }
            @Override public PageCapture back() { return null; }
            @Override public void clear() { cleared[0] = true; }
        };
        WebSession strict = new WebSession(redirecting, UrlPolicy.publicOnly(), null);
        var error = assertThrows(IllegalArgumentException.class, () -> strict.open("http://93.184.215.14/"));
        assertTrue(error.getMessage().startsWith("Blocked after redirect"));
        assertTrue(cleared[0], "the blocked page is left for about:blank");
        assertThrows(IllegalStateException.class, strict::links);
    }

    private WebSession searching() {
        WebSearchPort search = query -> new SearchResults(List.of(
                new SearchHit("Article", base + "/article", "About Camoufox"),
                new SearchHit("Duplicate", base + "/article", "same target"),
                new SearchHit("About", base + "/about", "")), List.of("ATTEMPT: local ORGANIC_RESULTS"));
        return new WebSession(browser, UrlPolicy.off(), search);
    }

    @Test void searchResultsAreNumberedAndFollowable() throws Exception {
        WebSession searching = searching();
        String answer = searching.search("camoufox");
        assertEquals("result-1: Article — " + base + "/article\n   About Camoufox\n"
                + "result-2: About — " + base + "/about\n"
                + "Open a result with web_follow link_id=\"result-1\".\n"
                + "ATTEMPT: local ORGANIC_RESULTS", answer);
        assertTrue(searching.follow("result-2").contains("TITLE: About"));
    }

    @Test void searchThenLinksThenFollowPicksTheSearchResult() throws Exception {
        WebSession searching = searching();
        searching.open(base + "/");
        searching.search("camoufox");
        var error = assertThrows(IllegalStateException.class, searching::links);
        assertTrue(error.getMessage().contains("web_follow link_id=\"result-1\""), error.getMessage());
        assertTrue(searching.follow("1").contains("TITLE: Article"), "bare 1 = result-1 of the last search");
        assertTrue(searching.follow("2").contains("TITLE: About"), "still the result list until web_links");
    }

    @Test void searchAndPageSnapshotsDoNotOverwriteEachOther() throws Exception {
        WebSession searching = searching();
        searching.search("camoufox");
        searching.follow("result-1");              // Article page: link-1 = Home
        searching.links();
        assertTrue(searching.follow("1").contains("TITLE: Start"), "after web_links a bare 1 is link-1");
        assertTrue(searching.follow("result-2").contains("TITLE: About"), "results survive page navigation");
        var unknown = assertThrows(IllegalArgumentException.class, () -> searching.follow("result-7"));
        assertTrue(unknown.getMessage().contains("result-1 to result-2"), unknown.getMessage());
    }

    @Test void pageLinkIdsAreInvalidAfterANewSearch() throws Exception {
        WebSession searching = searching();
        searching.open(base + "/");
        searching.links();
        searching.search("camoufox");
        var error = assertThrows(IllegalArgumentException.class, () -> searching.follow("link-1"));
        assertTrue(error.getMessage().contains("call web_links first"));
    }

    @Test void unconfiguredSearchFailsWithAHelpfulMessage() {
        var error = assertThrows(IllegalStateException.class, () -> web.search("anything"));
        assertTrue(error.getMessage().contains("web_open"));
    }
}
