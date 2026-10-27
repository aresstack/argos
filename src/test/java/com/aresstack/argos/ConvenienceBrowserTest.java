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
 * thread, one navigation per domain at a time, and the session's id navigation (select, follow, back, close).
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

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void loadsTabsInParallelAndNavigatesById() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
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
            try { Thread.sleep(path.startsWith("/slow") ? 1_500 : 0); } catch (InterruptedException ignored) { }
            String next = path.equals("/slow-a") ? "/next" : "/slow-a";
            byte[] body = ("<html><head><title>" + host + path + "</title></head><body><p>Seite " + path
                    + "</p><a href=\"" + next + "\">weiter</a></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
            hits.add(new Hit(host, path, start, System.nanoTime()));
        });
        site.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            int port = site.getAddress().getPort();
            String a = "http://localhost:" + port + "/slow-a";
            String b = "http://127.0.0.1:" + port + "/slow-b";
            String c = "http://localhost:" + port + "/slow-c";
            DomainCoordinator coordinator = new DomainCoordinator(4, System::currentTimeMillis);

            List<TabLoad> loads = browser.loadTabs(List.of(
                    new TabLoadRequest(null, a, RegistrableDomain.of(a)),
                    new TabLoadRequest(null, b, RegistrableDomain.of(b)),
                    new TabLoadRequest(null, c, RegistrableDomain.of(c))), coordinator, 4, 30_000);

            for (TabLoad load : loads) assertEquals(TabLoadOutcome.OK, load.outcome(), load.toString());
            assertEquals(3, loads.stream().map(TabLoad::tab).distinct().count(), "a new tab each");
            assertEquals("localhost/slow-a", loads.get(0).capture().title());
            assertEquals("127.0.0.1/slow-b", loads.get(1).capture().title());
            Hit hitA = find(hits, "localhost", "/slow-a"), hitB = find(hits, "127.0.0.1", "/slow-b"),
                    hitC = find(hits, "localhost", "/slow-c");
            assertTrue(hitA.overlaps(hitB), "different domains load at the same time: " + hits);
            assertFalse(hitA.overlaps(hitC), "the same domain waits: " + hits);
            assertTrue(coordinator.activeDomains().isEmpty(), "every slot is released");
            loads.forEach(load -> browser.closeTabQuietly(load.tab()));

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
        } finally {
            site.stop(0);
        }
    }

    private static Hit find(List<Hit> hits, String host, String path) {
        synchronized (hits) {
            return hits.stream().filter(hit -> hit.host().equals(host) && hit.path().equals(path)).findFirst()
                    .orElseThrow(() -> new AssertionError("no request for " + host + path + ": " + hits));
        }
    }
}
