package com.aresstack.argos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CamoufoxBrowserTest {
    @TempDir Path temporary;

    @Test @Timeout(value=160,unit=TimeUnit.SECONDS)
    void navigatesInspectsAndInteractsThroughOnlyCamoufox() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        byte[] html = "<html><head><title>Argos smoke</title></head><body><h1 id='subject'>Browser research</h1><button id='click' onclick=\"document.querySelector('#subject').textContent='Clicked successfully'\">Click</button><input id='search'/></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer site = HttpServer.create(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(),
                temporary.resolve("profile"), true))) {
            String url = "http://localhost:" + site.getAddress().getPort() + "/";
            Map<String,Object> navigated = browser.navigate(url);
            assertEquals("Argos smoke", navigated.get("title"));
            assertTrue(browser.snapshot(8000).get("text").toString().contains("Browser research"));
            assertFalse(browser.inspect("#subject", 5).isEmpty());
            assertTrue(browser.screenshot().length > 500);
            browser.fill("#search", "inspection");
            assertEquals("inspection", browser.evaluate(
                    "() => document.querySelector('#search').value"));
            browser.click("#click");
            assertEquals("Clicked successfully", browser.evaluate(
                    "() => document.querySelector('#subject').textContent"));
            assertFalse(browser.networkEvents().isEmpty());
        } finally {
            site.stop(0);
        }
    }

    @Test @Timeout(value=160,unit=TimeUnit.SECONDS)
    void recordsMaskedNetworkDetailsWithoutTouchingThePage() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        byte[] html = ("<html><head><title>Network</title></head><body><p id='out'>…</p><script>"
                + "fetch('/api/data?q=x&token=abc').then(r=>r.json()).then(d=>document.querySelector('#out').textContent=d.name)"
                + "</script></body></html>").getBytes(StandardCharsets.UTF_8);
        byte[] json = "{\"name\":\"Ada\",\"access_token\":\"top-secret\"}".getBytes(StandardCharsets.UTF_8);
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            boolean api = exchange.getRequestURI().getPath().startsWith("/api/");
            byte[] body = api ? json : html;
            exchange.getResponseHeaders().add("Content-Type", api ? "application/json" : "text/html; charset=utf-8");
            if (api) exchange.getResponseHeaders().add("Set-Cookie", "sid=cookie-secret; Path=/; HttpOnly");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(),
                temporary.resolve("profile"), true, null, "none", null, "argos", ToolProfile.EXPERT, true))) {
            browser.navigate("http://localhost:" + site.getAddress().getPort() + "/");
            assertEquals("Ada", browser.evaluate("() => document.querySelector('#out').textContent"));
            var api = browser.networkEvents("/api/data");
            assertEquals(1, api.size());
            String id = (String) api.get(0).get("id");
            Map<String, Object> detail = browser.networkDetail(id, true);
            assertEquals("application/json", detail.get("mime"));
            String rendered = detail.toString() + browser.networkHar(50);
            assertTrue(rendered.contains("Ada"));
            assertFalse(rendered.contains("top-secret"));
            assertFalse(rendered.contains("cookie-secret"));
            assertFalse(rendered.contains("token=abc"));
            var data = browser.networkJson(null, 20);
            assertEquals(id, data.get(0).get("id"));
            assertEquals("{name, access_token}", data.get(0).get("shape"));
        } finally {
            site.stop(0);
        }
    }

    /**
     * Heavy page: 60 JSON fetches, a 1.5 MB JSON body, a response that finishes only after
     * 4 s and one that sends headers plus a partial body and then never finishes.
     * The network tools must stay fast and the browser must remain usable afterwards.
     */
    @Test @Timeout(value=180,unit=TimeUnit.SECONDS)
    void networkToolsKeepTheBrowserUsableWithStalledAndHeavyResponses() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        byte[] html = ("<html><head><title>Heavy</title></head><body><p id='done'>loading</p><script>"
                + "fetch('/api/stall').catch(()=>{});"
                + "const jobs=[...Array(60).keys()].map(i=>fetch('/api/item?i='+i).then(r=>r.json()));"
                + "jobs.push(fetch('/api/big').then(r=>r.json()));"
                + "jobs.push(fetch('/api/slow').then(r=>r.json()));"
                + "Promise.all(jobs).then(()=>document.querySelector('#done').textContent='done');"
                + "</script></body></html>").getBytes(StandardCharsets.UTF_8);
        StringBuilder big = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < 30_000; i++) big.append(i == 0 ? "" : ",").append("{\"n\":").append(i).append(",\"t\":\"row\"}");
        byte[] bigJson = big.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(bigJson.length > 500_000 && bigJson.length < 2_000_000);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Concurrent handlers, otherwise the stalled response would block every other request.
        site.setExecutor(Executors.newCachedThreadPool());
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            try (var out = exchange.getResponseBody()) {
                if (path.equals("/api/stall")) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    out.write("{\"partial\":".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    release.await(150, TimeUnit.SECONDS);
                    return;
                }
                if (path.equals("/api/slow")) Thread.sleep(4_000);
                byte[] body = path.equals("/") ? html : path.equals("/api/big") ? bigJson
                        : "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", path.equals("/") ? "text/html" : "application/json");
                exchange.sendResponseHeaders(200, body.length);
                out.write(body);
            } catch (InterruptedException | java.io.IOException stopped) {
                // Client went away or the test released the stall.
            }
        });
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(),
                temporary.resolve("profile"), true, null, "none", null, "argos", ToolProfile.EXPERT, false))) {
            String origin = "http://localhost:" + site.getAddress().getPort();
            browser.navigate(origin + "/");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!"done".equals(browser.evaluate("() => document.querySelector('#done').textContent"))) {
                assertTrue(System.nanoTime() < deadline, "page fetches did not complete");
                Thread.sleep(250);
            }

            // Repeated listing loads the remaining bodies within the per-call budget.
            for (int round = 0; round < 4; round++) {
                long started = System.nanoTime();
                var rows = browser.networkJson(null, 20);
                assertFalse(rows.isEmpty());
                assertTrue(millisSince(started) < 10_000, "browser_network_json took " + millisSince(started) + " ms");
            }

            var stalled = browser.networkEvents("/api/stall");
            assertEquals(1, stalled.size());
            long started = System.nanoTime();
            Map<String, Object> stalledDetail = browser.networkDetail((String) stalled.get(0).get("id"), true);
            assertEquals("still loading", stalledDetail.get("bodyUnavailable"));
            assertTrue(millisSince(started) < 5_000, "detail on stalled response took " + millisSince(started) + " ms");

            String bigId = (String) browser.networkEvents("/api/big").get(0).get("id");
            started = System.nanoTime();
            Map<String, Object> bigDetail = browser.networkDetail(bigId, true);
            assertEquals(true, bigDetail.get("bodyTruncated"));
            assertTrue(millisSince(started) < 10_000, "detail on large response took " + millisSince(started) + " ms");

            // The single driver thread is still responsive.
            started = System.nanoTime();
            assertEquals(2, ((Number) browser.evaluate("() => 1 + 1")).intValue());
            assertTrue(millisSince(started) < 5_000);
            assertEquals("Heavy", browser.navigate(origin + "/").get("title"));
        } finally {
            release.countDown();
            site.stop(0);
        }
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
