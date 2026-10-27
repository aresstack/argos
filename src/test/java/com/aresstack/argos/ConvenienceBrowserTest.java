package com.aresstack.argos;

import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoad;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoadOutcome;
import com.aresstack.argos.convenience.ConvenienceBrowser.TabLoadRequest;
import com.aresstack.argos.convenience.ConvenienceSession;
import com.aresstack.argos.convenience.DomainCoordinator;
import com.aresstack.argos.convenience.RegistrableDomain;
import com.aresstack.argos.search.ParallelWebSearch;
import com.aresstack.argos.web.UrlPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The convenience path on real Camoufox (opt-in: -PtestCamoufox): parallel loads in new tabs on the single driver
 * thread (servers slow before their headers, and slow while their body streams), one navigation per domain at a
 * time, and the session's id navigation (select, follow, back, close).
 * "localhost" and "127.0.0.1" are two domains for the coordinator. Run locally:
 * {@code .\gradlew.bat test -PtestCamoufox=true --tests "*ConvenienceBrowserTest*"}
 */
class ConvenienceBrowserTest {
    @TempDir Path temporary;

    private record Hit(String host, String path, long start, long end) {
        boolean overlaps(Hit other) {
            return start < other.end && other.start < end;
        }
    }

    /** How a slow page is slow: before its headers (commit polled) or while its body streams (load polled). */
    private enum Delay { BEFORE_HEADERS, AFTER_FIRST_BYTES }

    /**
     * The server holds its response headers for 2.5 s, longer than the commit slice of a start: the first start
     * must give up waiting for the commit and the second navigation must begin while the first server still stalls.
     */
    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void loadsInParallelWhileServersDelayTheirHeaders() throws Exception {
        loadsInParallel(Delay.BEFORE_HEADERS);
    }

    /**
     * The server sends headers and the first body bytes at once (Firefox commits), then holds the rest for 2.5 s:
     * committed documents of different domains keep loading at the same time.
     */
    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void loadsInParallelWhileBodiesAreStillStreaming() throws Exception {
        loadsInParallel(Delay.AFTER_FIRST_BYTES);
    }

    private void loadsInParallel(Delay delay) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
        HttpServer site = site(hits, delay);
        try (CamoufoxBrowser browser = browser(local)) {
            int port = site.getAddress().getPort();
            String a = "http://localhost:" + port + "/slow-a";
            String b = "http://127.0.0.1:" + port + "/slow-b";
            String c = "http://localhost:" + port + "/slow-c";
            DomainCoordinator coordinator = new DomainCoordinator(4, System::currentTimeMillis);
            // The browser starts lazily with its first call; Camoufox's launch must not count as load time.
            String warm = "http://localhost:" + port + "/warm";
            TabLoad warmUp = browser.loadTabs(List.of(new TabLoadRequest(null, warm, RegistrableDomain.of(warm))),
                    coordinator, 1, 60_000).get(0);
            assertEquals(TabLoadOutcome.OK, warmUp.outcome(), warmUp.toString());
            browser.closeTabQuietly(warmUp.tab());

            long started = System.nanoTime();
            List<TabLoad> loads = browser.loadTabs(List.of(
                    new TabLoadRequest(null, a, RegistrableDomain.of(a)),
                    new TabLoadRequest(null, b, RegistrableDomain.of(b)),
                    new TabLoadRequest(null, c, RegistrableDomain.of(c))), coordinator, 4, 30_000);
            long millis = (System.nanoTime() - started) / 1_000_000;

            for (TabLoad load : loads) assertEquals(TabLoadOutcome.OK, load.outcome(), load.toString());
            assertEquals(3, loads.stream().map(TabLoad::tab).distinct().count(), "a new tab each");
            assertEquals("localhost/slow-a", loads.get(0).capture().title());
            assertEquals("127.0.0.1/slow-b", loads.get(1).capture().title());
            assertTrue(loads.get(1).capture().text().contains("Ende"), "the whole body was read");
            Hit hitA = find(hits, "localhost", "/slow-a"), hitB = find(hits, "127.0.0.1", "/slow-b"),
                    hitC = find(hits, "localhost", "/slow-c");
            assertTrue(hitA.overlaps(hitB), delay + ": different domains load at the same time: " + hits);
            assertFalse(hitA.overlaps(hitC), delay + ": the same domain waits: " + hits);
            // Sequential would be 3 x 2.5 s; parallel a and b, then c.
            assertTrue(millis < 6_500, delay + ": took " + millis + " ms, server intervals " + intervals(hits, started));
            assertTrue(coordinator.activeDomains().isEmpty(), "every slot is released");
        } finally {
            site.stop(0);
        }
    }

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void navigatesById() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        HttpServer site = site(Collections.synchronizedList(new ArrayList<>()), null);
        try (CamoufoxBrowser browser = browser(local)) {
            int port = site.getAddress().getPort();
            String a = "http://localhost:" + port + "/slow-a";
            String b = "http://127.0.0.1:" + port + "/slow-b";
            DomainCoordinator coordinator = new DomainCoordinator(4, System::currentTimeMillis);
            ConvenienceSession session = new ConvenienceSession(browser, coordinator, UrlPolicy.from(TestNetworks.LOOPBACK),
                    query -> List.of(new ParallelWebSearch.EngineRun("bing", ParallelWebSearch.EngineStatus.OK, "",
                            List.of(new ParallelWebSearch.EngineHit(1, "A", a, ""),
                                    new ParallelWebSearch.EngineHit(2, "B", b, "")), null, "")), null);
            session.search("lokal");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> opened = (List<Map<String, Object>>) session
                    .selectResults("s1", List.of("r-s1-1", "r-s1-2")).get("opened");
            assertEquals(2, opened.size(), opened.toString());
            String tab = (String) opened.get(0).get("tabId");
            Map<String, Object> next = session.followLink(tab, "l-" + tab + "-1");
            assertEquals("localhost/next", next.get("title"));
            Map<String, Object> back = session.back(tab);
            assertEquals("localhost/slow-a", back.get("title"));
            assertEquals("127.0.0.1/slow-b", session.readTab((String) opened.get(1).get("tabId"), 1).get("title"));
            session.closeTabs(List.of(tab));
            assertFalse(browser.hasOpenTab(tab));
            assertTrue(browser.hasOpenTab((String) opened.get(1).get("tabId")));
            assertTrue(coordinator.activeDomains().isEmpty(), "every slot is released");
        } finally {
            site.stop(0);
        }
    }

    private CamoufoxBrowser browser(ArgosConfig local) {
        return new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, local.executable(), local.launchOptions(),
                temporary.resolve("profile"), true).withNetwork(TestNetworks.LOOPBACK));
    }

    /** /slow-* pages wait 2.5 s as {@code delay} says (null: no wait); each request's interval is logged. */
    private static HttpServer site(List<Hit> hits, Delay delay) throws Exception {
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            long start = System.nanoTime();
            String path = exchange.getRequestURI().getPath();
            String host = exchange.getRequestHeaders().getFirst("Host").replaceAll(":\\d+$", "");
            if (path.equals("/favicon.ico")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            boolean slow = delay != null && path.startsWith("/slow");
            String next = path.equals("/slow-a") ? "/next" : "/slow-a";
            byte[] head = ("<html><head><title>" + host + path + "</title></head><body><p>Seite " + path + "</p>")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] tail = ("<a href=\"" + next + "\">weiter</a><p>Ende</p></body></html>").getBytes(StandardCharsets.UTF_8);
            try {
                if (slow && delay == Delay.BEFORE_HEADERS) Thread.sleep(2_500);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, 0); // chunked: the first bytes go out before the rest exists
                try (var out = exchange.getResponseBody()) {
                    out.write(head);
                    out.flush();
                    if (slow && delay == Delay.AFTER_FIRST_BYTES) Thread.sleep(2_500);
                    out.write(tail);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            hits.add(new Hit(host, path, start, System.nanoTime()));
        });
        site.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        site.start();
        return site;
    }

    /** Each request as host+path start..end in ms relative to {@code origin}, for the failure message. */
    private static String intervals(List<Hit> hits, long origin) {
        synchronized (hits) {
            return hits.stream().map(hit -> hit.host() + hit.path() + " " + (hit.start() - origin) / 1_000_000 + ".."
                    + (hit.end() - origin) / 1_000_000).toList().toString();
        }
    }

    private static Hit find(List<Hit> hits, String host, String path) {
        synchronized (hits) {
            return hits.stream().filter(hit -> hit.host().equals(host) && hit.path().equals(path)).findFirst()
                    .orElseThrow(() -> new AssertionError("no request for " + host + path + ": " + hits));
        }
    }
}
