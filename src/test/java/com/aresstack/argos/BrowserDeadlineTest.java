package com.aresstack.argos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deadlines of the single driver thread, without a browser: queued calls never restart it, stalled ones do. */
class BrowserDeadlineTest {
    @TempDir Path temporary;

    private CamoufoxBrowser browser() {
        return new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null, temporary, true));
    }

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aCallThatOnlyWaitedBehindAnotherTaskFailsAsBusyAndLeavesTheRunningTaskAlone() throws Exception {
        try (CamoufoxBrowser browser = browser()) {
            CountDownLatch running = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            CompletableFuture<Integer> longTask = CompletableFuture.supplyAsync(() -> browser.onBrowser(() -> {
                running.countDown();
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException stopped) {
                    interrupted.set(true);
                }
                return 1;
            }, 20));
            running.await();
            AtomicBoolean ran = new AtomicBoolean();
            IllegalStateException busy = assertThrows(IllegalStateException.class,
                    () -> browser.onBrowser(() -> ran.getAndSet(true), 1));
            assertTrue(busy.getMessage().contains("busy"), busy.getMessage());
            assertEquals(1, longTask.get(10, TimeUnit.SECONDS));
            assertFalse(interrupted.get(), "the running task was not stopped");
            assertFalse(ran.get(), "the cancelled call never ran");
            assertEquals(2, browser.onBrowser(() -> 2, 5));
        }
    }

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aStartedCallThatMissesItsDeadlineIsStoppedAndTheNextCallRuns() {
        try (CamoufoxBrowser browser = browser()) {
            AtomicBoolean interrupted = new AtomicBoolean();
            long started = System.nanoTime();
            IllegalStateException stalled = assertThrows(IllegalStateException.class, () -> browser.onBrowser(() -> {
                try {
                    Thread.sleep(20_000);
                } catch (InterruptedException stopped) {
                    interrupted.set(true);
                }
                return null;
            }, 1));
            assertInstanceOf(CamoufoxBrowser.BrowserStalled.class, stalled);
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000);
            assertEquals(3, browser.onBrowser(() -> 3, 5));
            assertTrue(interrupted.get(), "the stalled task was interrupted");
        }
    }
}
