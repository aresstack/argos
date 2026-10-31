package com.aresstack.argos;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void aProcessThatDoesNotExitInTimeIsKilledAndAwaited() throws Exception {
        Process stubborn = sleeper();
        try {
            List<ProcessHandle> left = BrowserLiveness.stop(List.of(stubborn.toHandle()), Duration.ofMillis(100),
                    Duration.ofSeconds(10));
            assertTrue(left.isEmpty(), "still running: " + left);
            assertFalse(stubborn.isAlive(), "the kill was awaited, not only sent");
        } finally {
            stubborn.destroyForcibly();
        }
    }

    @Test
    void anExitedSessionNeedsNoKill() throws Exception {
        Process ended = sleeper();
        ended.destroy();
        ended.waitFor();
        assertTrue(BrowserLiveness.stop(List.of(ended.toHandle()), Duration.ofSeconds(1), Duration.ofSeconds(1))
                .isEmpty());
    }

    @Test
    void aBrowserThatStillRunsBlocksTheNextStartOnItsProfile() throws Exception {
        Process leftover = sleeper();
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> BrowserLiveness.requireEnded(List.of(leftover.toHandle()), Duration.ofMillis(100)));
            assertTrue(refused.getMessage().contains(String.valueOf(leftover.pid())), refused.getMessage());
            assertTrue(refused.getMessage().contains("does not start a second browser"), refused.getMessage());
        } finally {
            leftover.destroyForcibly();
            leftover.waitFor();
        }
        BrowserLiveness.requireEnded(List.of(leftover.toHandle()), Duration.ofMillis(100));
        BrowserLiveness.requireEnded(List.of(), Duration.ZERO);
    }

    /** A process that runs until it is killed: a JVM sleeping for a minute. */
    private static Process sleeper() throws Exception {
        Path source = Files.createTempFile("argos-sleeper", ".java");
        Files.writeString(source, "class Sleeper { public static void main(String[] a) throws Exception {"
                + " Thread.sleep(60_000); } }");
        source.toFile().deleteOnExit();
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                source.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
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
