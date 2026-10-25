package com.aresstack.argos;

import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in ({@code -PtestCamoufox=true}): a browser call that never returns must not hold the single driver thread.
 * The call fails at its deadline, the browser is restarted, and the next call works.
 */
class BrowserStallRecoveryTest {
    @TempDir Path temporary;

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aStalledCallFailsAtItsDeadlineAndTheBrowserComesBack() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        byte[] html = "<html><head><title>Back</title></head><body>still here</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        site.start();
        String url = "http://localhost:" + site.getAddress().getPort() + "/";
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(TestNetworks.LOOPBACK))) {
            browser.navigate(url);
            long started = System.nanoTime();
            // Stands in for a protocol answer that never comes: waits on Playwright's connection without end.
            IllegalStateException stalled = assertThrows(IllegalStateException.class, () -> browser.inBrowserContext(
                    context -> {
                        context.pages().get(0).waitForCondition(() -> false,
                                new Page.WaitForConditionOptions().setTimeout(0));
                        return null;
                    }, 3));
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            assertTrue(stalled.getMessage().contains("did not answer"), stalled.getMessage());
            assertTrue(elapsed < 10_000, "failed at its deadline, took " + elapsed + " ms");
            assertEquals("Back", browser.navigate(url).get("title"));
            assertEquals(1, browser.tabs().size());
        } finally {
            site.stop(0);
        }
    }
}
