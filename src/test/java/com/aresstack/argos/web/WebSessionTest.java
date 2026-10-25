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
        web = new WebSession(browser, new UrlPolicy(true), null, 40, 50);
    }

    @AfterEach void stopSite() {
        site.stop(0);
    }

    @Test void opensAPageAndReportsTitleTextAndLinkCount() {
        String answer = web.open(base + "/");
        assertTrue(answer.startsWith("URL: " + base + "/\nTITLE: Start\nLINKS: 2"), answer);
        assertTrue(answer.contains("Welcome"));
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

    @Test void reportsTheFinalUrlAfterRedirects() {
        assertTrue(web.open(base + "/moved").startsWith("URL: " + base + "/article"));
    }

    @Test void requiresAPageBeforeReading() {
        assertThrows(IllegalStateException.class, () -> web.read(1));
    }

    @Test void strictPolicyBlocksLoopbackAndNonWebUrls() {
        WebSession strict = new WebSession(browser, new UrlPolicy(false), null);
        assertThrows(IllegalArgumentException.class, () -> strict.open(base + "/"));
        assertThrows(IllegalArgumentException.class, () -> strict.open("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> strict.open("javascript:alert(1)"));
        for (String internal : List.of("http://[fd00::1]/admin", "http://[fc00::2]/", "http://[::1]/",
                "http://[fe80::1]/", "http://10.0.0.1/", "http://192.168.1.1/", "http://100.64.0.1/",
                "http://[::ffff:127.0.0.1]/")) {
            assertThrows(IllegalArgumentException.class, () -> strict.open(internal), internal);
        }
        assertDoesNotThrow(() -> new UrlPolicy(false).check("http://[2606:4700::1111]/"));
    }

    @Test void strictPolicyAbandonsPagesThatRedirectIntoPrivateNetworks() {
        BrowserPagePort redirecting = new BrowserPagePort() {
            @Override public PageCapture open(String url, String referer) {
                return new PageCapture("http://127.0.0.1/admin", "Router", "secret", "");
            }
            @Override public PageCapture current() { return open(null, null); }
            @Override public PageCapture back() { return null; }
        };
        WebSession strict = new WebSession(redirecting, new UrlPolicy(false), null);
        var error = assertThrows(IllegalArgumentException.class, () -> strict.open("http://93.184.215.14/"));
        assertTrue(error.getMessage().startsWith("Blocked after redirect"));
        assertThrows(IllegalStateException.class, strict::links);
    }

    @Test void searchResultsAreNumberedAndFollowable() throws Exception {
        WebSearchPort search = query -> new SearchResults(List.of(
                new SearchHit("Article", base + "/article", "About Camoufox"),
                new SearchHit("Duplicate", base + "/article", "same target"),
                new SearchHit("About", base + "/about", "")), List.of("ATTEMPT: local ORGANIC_RESULTS"));
        WebSession searching = new WebSession(browser, new UrlPolicy(true), search);
        String answer = searching.search("camoufox");
        assertEquals("1: Article — " + base + "/article\n   About Camoufox\n"
                + "2: About — " + base + "/about\n"
                + "Open a result with web_follow link_id=\"1\" or web_open url=\"...\".\n"
                + "ATTEMPT: local ORGANIC_RESULTS", answer);
        assertTrue(searching.follow("2").contains("TITLE: About"));
    }

    @Test void unconfiguredSearchFailsWithAHelpfulMessage() {
        var error = assertThrows(IllegalStateException.class, () -> web.search("anything"));
        assertTrue(error.getMessage().contains("web_open"));
    }
}
