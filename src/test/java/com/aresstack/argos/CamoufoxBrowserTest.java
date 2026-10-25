package com.aresstack.argos;

import com.aresstack.argos.network.NetworkSettings;
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
                temporary.resolve("profile"), true)
                // The smoke site is on loopback, which the strict network policy only admits by rule.
                .withNetwork(NetworkSettings.parse("strict", "127.0.0.1,::1", null, null)))) {
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
}
