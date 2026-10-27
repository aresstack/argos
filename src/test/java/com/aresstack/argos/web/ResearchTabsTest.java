package com.aresstack.argos.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.aresstack.argos.web.WebSessionTest.idOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Research tabs (convenience profile) against local sites on three loopback hosts: results open in parallel
 * tabs, every page answer carries its links, link ids belong to their tab, URLs stay exact and internal.
 */
class ResearchTabsTest {
    /** ResearchGate-style session parameters that must survive byte for byte. */
    static final String RG_QUERY = "?_sg%5B0%5D=Zx9-AbC_dEf.ghI&_sg%5B1%5D=q%2Fw%2Be&_tp=eyJjb250ZXh0Ijp7ImZpcnN0UGFnZSI6Il9kaXJlY3QifX0%3D";

    private final List<HttpServer> servers = new ArrayList<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    /** Raw request targets (path + query) as the servers received them. */
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private String a;
    private String b;
    private String c;
    private MultiTabBrowser browser;
    private TestClock clock;
    private WebSession web;

    static final class TestClock implements HostThrottle.Clock {
        long now = 1_000_000;
        final List<Long> sleeps = new ArrayList<>();

        @Override public long millis() { return now; }

        @Override public void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }

    @BeforeEach void start() throws Exception {
        a = serve("127.0.0.1");
        b = serve("127.0.0.2");
        c = serve("127.0.0.3");
        browser = new MultiTabBrowser();
        clock = new TestClock();
        web = new WebSession(browser, UrlPolicy.off(), query -> new SearchResults(List.of(
                new SearchHit("Smart glasses survey", a + "/survey", "A survey"),
                new SearchHit("Paper on ResearchGate", b + "/publication/42_Glasses" + RG_QUERY, "Full text"),
                new SearchHit("Vendor blog", c + "/blog", "Blog"),
                new SearchHit("Survey appendix", a + "/appendix", "Same host as the survey")), List.of()),
                12_000, 150, new HostThrottle(clock, 1_500, 60_000));
        web.useResearchTabs(true);
    }

    private String serve(String address) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(address), 0), 0);
        server.createContext("/", exchange -> {
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            requested.add(address + target);
            String path = exchange.getRequestURI().getPath();
            String html = switch (path) {
                case "/survey" -> "<title>Survey</title><p>Smart glasses market survey.</p>"
                        + "<a href='/survey/chapter-2'>Chapter 2</a> <a href='/'>Home</a>";
                case "/survey/chapter-2" -> "<title>Chapter 2</title><p>Displays and batteries.</p><a href='/survey'>Back to survey</a>";
                case "/publication/42_Glasses" -> "<title>Glasses paper</title><p>Abstract of the paper.</p>"
                        + "<a href='/publication/43_Follow-up" + RG_QUERY.replace("&", "&amp;") + "'>Follow-up paper</a>";
                case "/publication/43_Follow-up" -> "<title>Follow-up</title><p>Second paper.</p>";
                case "/blog" -> "<title>Blog</title><p>Vendor news.</p><a href='/blog/2'>Older posts</a>";
                case "/appendix" -> "<title>Appendix</title><p>Tables.</p>";
                default -> "<title>Home</title><p>Start page of " + address + "</p>";
            };
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        servers.add(server);
        return "http://" + address + ":" + server.getAddress().getPort();
    }

    @AfterEach void stop() {
        servers.forEach(server -> server.stop(0));
    }

    /** Tests 1 and 2: ids without URLs; several results load in parallel, each in its own tab. */
    @Test void selectedResultsOpenInParallelTabsWithContentAndLinks() throws Exception {
        String results = web.search("smart glasses");
        assertFalse(results.contains("http"), "no target URL in search results: " + results);
        assertFalse(results.contains("_sg") || results.contains("_tp"), results);

        String answer = web.openResult("result-1, result-2, result-3");

        assertEquals(List.of(List.of(a + "/survey", b + "/publication/42_Glasses" + RG_QUERY, c + "/blog")),
                browser.waves, "three hosts load in one parallel wave");
        assertTrue(answer.startsWith("Opened 3 results in tabs t2, t3, t4.\n"), answer);
        assertTrue(answer.contains("=== result-1 in tab t2 ===\nTAB: t2\nTITLE: Survey\n"), answer);
        assertTrue(answer.contains("=== result-2 in tab t3 ===\nTAB: t3\nTITLE: Glasses paper\n"), answer);
        assertTrue(answer.contains("=== result-3 in tab t4 ===\nTAB: t4\nTITLE: Blog\n"), answer);
        assertTrue(answer.contains("Chapter 2") && answer.contains("Follow-up paper") && answer.contains("Older posts"),
                "every page answer lists its links: " + answer);
        assertFalse(answer.contains("http"), "no URL in page answers: " + answer);
        assertEquals("t2", browser.currentTab(), "the first opened result becomes current");
        assertEquals("t1", "t1", "the search tab stays");
    }

    /** Tests 4, 6 and 8: a link id names its tab; following it moves only that tab. */
    @Test void linkIdsBelongToTheirTabAndOtherTabsStay() throws Exception {
        web.search("smart glasses");
        String answer = web.openResult("result-1, result-3");
        String chapter = idOf(answer, "Chapter 2");
        String older = idOf(answer, "Older posts");
        browser.showTab("t3");                                   // the agent looks at the blog tab

        String followed = web.follow(chapter);                    // ...and follows a link of the survey tab

        assertTrue(followed.startsWith("TAB: t2\nTITLE: Chapter 2\n"), followed);
        assertTrue(browser.currentUrl("t3").endsWith("/blog"), "the blog tab is untouched");
        assertTrue(web.read(1, "t3").contains("TITLE: Blog"));
        assertTrue(web.read(1, "t3").contains(older + ": Older posts"), "ids of an unchanged page stay valid");
        var stale = assertThrows(IllegalArgumentException.class, () -> web.follow(chapter));
        assertTrue(stale.getMessage().startsWith("Stale link id: " + chapter), stale.getMessage());
    }

    /** Test 5: query parameters such as _sg and _tp are kept exactly, also through web_get_url. */
    @Test void urlsWithSessionParametersAreUsedExactly() throws Exception {
        web.search("smart glasses");
        String paper = web.openResult("result-2");
        assertTrue(requested.contains("127.0.0.2/publication/42_Glasses" + RG_QUERY), requested.toString());
        String followUp = idOf(paper, "Follow-up paper");
        assertEquals(b + "/publication/43_Follow-up" + RG_QUERY, web.url(followUp), "web_get_url gives the stored URL");
        assertEquals(b + "/publication/42_Glasses" + RG_QUERY, web.url("result-2"));
        int navigations = browser.navigated.size();
        web.url(followUp);
        assertEquals(navigations, browser.navigated.size(), "web_get_url never navigates");

        assertTrue(web.follow(followUp).contains("TITLE: Follow-up"));
        assertTrue(requested.contains("127.0.0.2/publication/43_Follow-up" + RG_QUERY), requested.toString());
    }

    /** Test 7: ids of an earlier search, of a closed tab and unknown ids are rejected. */
    @Test void staleAndUnknownReferencesAreRejected() throws Exception {
        web.search("smart glasses");
        String survey = web.openResult("result-1");
        String chapter = idOf(survey, "Chapter 2");
        web.search("smart glasses again");                       // result-5 to result-8 now

        var oldResult = assertThrows(IllegalArgumentException.class, () -> web.openResult("result-1"));
        assertTrue(oldResult.getMessage().startsWith("Stale result id: result-1 belongs to an earlier search."),
                oldResult.getMessage());
        assertThrows(IllegalArgumentException.class, () -> web.url("result-1"));
        assertTrue(web.follow(chapter).contains("TITLE: Chapter 2"), "the research tab survived the new search");

        web.closeTab("t2");
        var closed = assertThrows(IllegalArgumentException.class, () -> web.url(idOf(survey, "Home")));
        assertTrue(closed.getMessage().startsWith("Stale link id"), closed.getMessage());
        var unknown = assertThrows(IllegalArgumentException.class, () -> web.follow("link-999"));
        assertTrue(unknown.getMessage().startsWith("Unknown link id: link-999."), unknown.getMessage());
        var wrongKind = assertThrows(IllegalArgumentException.class, () -> web.openResult("link-1"));
        assertTrue(wrongKind.getMessage().contains("is a page link, not a search result"), wrongKind.getMessage());
    }

    /** A new search runs in its own tab; open research tabs are not lost. */
    @Test void aNewSearchKeepsResearchTabs() throws Exception {
        web.search("smart glasses");
        web.openResult("result-1");                              // t2, now current
        String again = web.search("smart glasses battery");
        assertTrue(again.contains("Search ran in tab t1; research tabs stay open: t2."), again);
        assertTrue(browser.currentUrl("t2").endsWith("/survey"));
        assertTrue(web.tabs().contains("t2: Survey — from result-1, 1 page(s) read"), web.tabs());
    }

    /** Test 9: one host never loads twice at once, pacing between its navigations, cooldown after 429/403. */
    @Test void parallelLoadsArePacedPerHostAndBackOffAfterRefusals() throws Exception {
        web.search("smart glasses");
        web.openResult("result-1, result-4");                   // both on host a

        assertEquals(List.of(List.of(a + "/survey"), List.of(a + "/appendix")), browser.waves,
                "same host: two waves, not one parallel burst");
        assertEquals(List.of(1_500L), clock.sleeps, "the second wave waited for the host spacing");

        statuses.put("/blog", 429);
        String refused = web.openResult("result-3");
        assertTrue(refused.contains("HTTP error: 429"), refused);
        web.search("smart glasses");                             // result-5..8: same hosts again
        String later = web.openResult("result-7");               // the blog again
        assertTrue(later.contains("result-7: not opened: 127.0.0.3 refused recent requests (HTTP 429/403). Wait about 60 s"),
                later);
        String follow = idOf(web.read(1, "t2"), "Home");
        assertDoesNotThrow(() -> web.follow(follow), "other hosts are not affected");

        clock.now += 61_000;
        statuses.remove("/blog");
        assertTrue(web.openResult("result-7").contains("TITLE: Blog"), "after the cooldown the host is tried again");
    }

    /** Test 10: closing a finished tab leaves the others readable and their ids valid. */
    @Test void closingATabKeepsTheOthers() throws Exception {
        web.search("smart glasses");
        String answer = web.openResult("result-1, result-3");
        String older = idOf(answer, "Older posts");

        assertEquals("Closed tab t2. Open research tabs: t3.", web.closeTab("t2"));

        assertFalse(browser.hasTab("t2"));
        assertTrue(web.follow(older).startsWith("TAB: t3\n"), "ids of the other tab still work");
        assertTrue(web.tabs().contains("Closed: t2 (Survey, from result-1)"), web.tabs());
        assertThrows(IllegalArgumentException.class, () -> web.read(1, "t2"));
    }

    @Test void anAlreadyOpenResultIsNotLoadedTwice() throws Exception {
        web.search("smart glasses");
        web.openResult("result-2");
        int waves = browser.waves.size();
        String again = web.openResult("result-2, result-3");
        assertTrue(again.contains("result-2: already open in tab t2"), again);
        assertEquals(List.of(c + "/blog"), browser.waves.get(waves), "only the new result loads");
    }

    @Test void aLinkCanOpenInANewTabAndKeepItsPage() throws Exception {
        web.search("smart glasses");
        String survey = web.openResult("result-1");
        String answer = web.follow(idOf(survey, "Chapter 2"), true);
        assertTrue(answer.startsWith("TAB: t3\nTITLE: Chapter 2"), answer);
        assertTrue(browser.currentUrl("t2").endsWith("/survey"), "the survey stays open in its tab");
        assertTrue(web.tabs().contains("t3 (current): Chapter 2 — from " + idOf(survey, "Chapter 2") + " of t2"), web.tabs());
    }

    @Test void withoutResearchTabsResultsOpenOneAtATimeInTheShownTab() throws Exception {
        web.useResearchTabs(false);
        web.search("smart glasses");
        var several = assertThrows(IllegalArgumentException.class, () -> web.openResult("result-1, result-2"));
        assertTrue(several.getMessage().startsWith("Open one result at a time"), several.getMessage());
        String one = web.openResult("result-1");
        assertTrue(one.startsWith("TITLE: Survey\n"), one);
        assertEquals("t1", browser.currentTab());
        assertTrue(browser.waves.isEmpty(), "no parallel loading without research tabs");
    }
}
