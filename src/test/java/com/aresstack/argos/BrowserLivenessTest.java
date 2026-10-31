package com.aresstack.argos;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserLivenessTest {
    @Test
    void anOpenContextOfAConnectedBrowserIsAlive() {
        assertFalse(BrowserLiveness.gone(context(false, browser(true)), List.of()));
        assertFalse(BrowserLiveness.gone(context(false, null), List.of(ProcessHandle.current())));
    }

    @Test
    void aClosedContextOrADisconnectedBrowserIsGone() {
        assertTrue(BrowserLiveness.gone(context(true, browser(true)), List.of()));
        assertTrue(BrowserLiveness.gone(context(false, browser(false)), List.of()));
    }

    @Test
    void anEndedDriverIsGoneEvenBeforePlaywrightNoticed() throws Exception {
        Process ended = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        ended.waitFor();
        assertTrue(BrowserLiveness.gone(context(false, browser(true)), List.of(ended.toHandle())));
        assertTrue(BrowserLiveness.awaitExit(List.of(ended.toHandle()), Duration.ofSeconds(1)));
        assertFalse(BrowserLiveness.awaitExit(List.of(ProcessHandle.current()), Duration.ofMillis(50)));
    }

    private static BrowserContext context(boolean closed, Browser browser) {
        return (BrowserContext) Proxy.newProxyInstance(BrowserLivenessTest.class.getClassLoader(),
                new Class<?>[] {BrowserContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed" -> closed;
                    case "browser" -> browser;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Browser browser(boolean connected) {
        return (Browser) Proxy.newProxyInstance(BrowserLivenessTest.class.getClassLoader(),
                new Class<?>[] {Browser.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isConnected" -> connected;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
