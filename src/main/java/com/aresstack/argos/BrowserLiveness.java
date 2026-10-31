package com.aresstack.argos;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Whether the persistent Camoufox session is gone (its windows were closed by the user, it crashed), from
 * Playwright's own state only: the context's close event, the browser's disconnect, or a driver process that ended.
 * A failed call alone says nothing; a single closed tab fails with the same TargetClosedError, for example.
 */
final class BrowserLiveness {
    private BrowserLiveness() {
    }

    static boolean gone(BrowserContext context, List<ProcessHandle> driverProcesses) {
        if (context.isClosed()) return true;
        Browser browser = context.browser();
        if (browser != null && !browser.isConnected()) return true;
        return !driverProcesses.isEmpty() && driverProcesses.stream().noneMatch(ProcessHandle::isAlive);
    }

    /** The processes and everything running below them now; taken before the driver is told to stop. */
    static List<ProcessHandle> withDescendants(List<ProcessHandle> processes) {
        List<ProcessHandle> all = new ArrayList<>();
        for (ProcessHandle process : processes) {
            all.add(process);
            process.descendants().forEach(all::add);
        }
        return all;
    }

    /** Waits, at most {@code timeout} in total, until every process has exited. True when they all have. */
    static boolean awaitExit(List<ProcessHandle> processes, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (ProcessHandle process : processes) {
            long left = deadline - System.nanoTime();
            if (left <= 0) break;
            try {
                process.onExit().get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception stillRunning) {
                break;
            }
        }
        return processes.stream().noneMatch(ProcessHandle::isAlive);
    }
}
