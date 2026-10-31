package com.aresstack.argos;

import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in ({@code -PtestCamoufox=true}): Camoufox closed outside Argos (the user closes its windows, a crash) must not
 * leave Argos on a dead session. The next call starts the browser again with the same profile, without a restart
 * of Argos (issue #65).
 */
class BrowserClosedRecoveryTest {
    @TempDir Path temporary;
    private HttpServer site;
    private CountDownLatch slowRequested;
    private CountDownLatch slowReleased;

    @BeforeEach
    void startSite() throws Exception {
        slowRequested = new CountDownLatch(1);
        slowReleased = new CountDownLatch(1);
        site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/slow")) {
                slowRequested.countDown();
                try {
                    slowReleased.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] html = "<html><head><title>Back</title></head><body>still here</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        site.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        site.start();
    }

    @AfterEach
    void stopSite() {
        slowReleased.countDown();
        site.stop(0);
    }

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void theNextCallStartsTheBrowserAgainAfterItWasClosedOutsideArgos() throws Exception {
        try (CamoufoxBrowser browser = browser()) {
            assertEquals("Back", browser.navigate(url("/")).get("title"));
            closeCamoufoxLikeTheUser();
            // The same call that used to fail with TargetClosedError now works on a new browser, same profile.
            assertEquals("Back", browser.navigate(url("/")).get("title"));
            assertEquals(1, browser.tabs().size());
            closeCamoufoxLikeTheUser();
            assertEquals("Back", browser.navigate(url("/")).get("title"));
            assertEquals(1, browser.tabs().size());
        }
    }

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void aCallThatWasRunningWhenTheBrowserClosedFailsAndIsNotRepeated() throws Exception {
        try (CamoufoxBrowser browser = browser()) {
            assertEquals("Back", browser.navigate(url("/")).get("title"));
            CompletableFuture<Object> navigation = CompletableFuture.supplyAsync(() -> browser.navigate(url("/slow")));
            assertTrue(slowRequested.await(60, TimeUnit.SECONDS), "the navigation reached the site");
            closeCamoufoxLikeTheUser();
            Throwable failure = assertThrows(Exception.class, () -> navigation.get(90, TimeUnit.SECONDS)).getCause();
            assertInstanceOf(CamoufoxBrowser.BrowserClosed.class, failure, String.valueOf(failure));
            slowReleased.countDown();
            assertEquals("Back", browser.navigate(url("/")).get("title"));
            assertEquals(1, browser.tabs().size());
        }
    }

    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void closingTheLastTabIsNotAClosedBrowser() throws Exception {
        try (CamoufoxBrowser browser = browser()) {
            assertEquals("Back", browser.navigate(url("/")).get("title"));
            browser.inBrowserContext(context -> {
                context.pages().forEach(Page::close);
                return null;
            }, 30);
            // A closed tab is not a closed browser: no restart, the next call opens a tab in the same session.
            assertEquals("Back", browser.navigate(url("/")).get("title"));
            assertFalse(browser.tabs().isEmpty());
        }
    }

    private CamoufoxBrowser browser() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        return new CamoufoxBrowser(new ArgosConfig(
                "127.0.0.1", 0, local.executable(), local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(TestNetworks.LOOPBACK));
    }

    private String url(String path) {
        return "http://localhost:" + site.getAddress().getPort() + path;
    }

    /** Ends every Camoufox process below this JVM the way the operating system ends a closed application. */
    private static void closeCamoufoxLikeTheUser() throws Exception {
        List<ProcessHandle> camoufox = ProcessHandle.current().descendants()
                .filter(process -> process.info().command()
                        .map(command -> command.toLowerCase(Locale.ROOT).contains("camoufox")).orElse(false))
                .toList();
        assertFalse(camoufox.isEmpty(), "a Camoufox process runs below Argos");
        // Its helpers, too: one that kept the browser's pipe open would hide the close from Playwright.
        List<ProcessHandle> ended = BrowserLiveness.withDescendants(camoufox).stream().distinct().toList();
        ended.forEach(ProcessHandle::destroy);
        for (ProcessHandle process : ended) {
            process.onExit().get(30, TimeUnit.SECONDS);
        }
    }
}
