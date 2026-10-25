package com.aresstack.argos.fetch;

import com.aresstack.argos.ArgosConfig;
import com.aresstack.argos.CamoufoxBrowser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A real browser for opt-in tests: Camoufox with {@code -PtestCamoufox=true} (production engine), otherwise
 * Chromium with {@code -PtestChromium=/path/to/chrome} for the engine-independent background-tab logic.
 * Both drive Playwright from one dedicated thread, exactly like {@link CamoufoxBrowser}.
 */
public interface TestBrowserSession extends BrowserContextAccess, AutoCloseable {
    String engine();

    void openCurrent(String url);

    String currentText();

    @Override
    void close();

    static Optional<TestBrowserSession> start(Path temporary) throws Exception {
        if (Boolean.getBoolean("argos.testCamoufox")) {
            ArgosConfig local = ArgosConfig.fromEnvironment();
            local.requireBrowserConfiguration();
            CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, local.executable(),
                    local.launchOptions(), temporary.resolve("profile"), true));
            return Optional.of(new TestBrowserSession() {
                public String engine() { return "camoufox"; }
                public void openCurrent(String url) { browser.navigate(url); }
                public String currentText() { return browser.snapshot(60_000).get("text").toString(); }
                public String currentUrl() { return browser.currentUrl(); }
                public <T> T inBrowserContext(Function<BrowserContext, T> task, int timeoutSeconds) {
                    return browser.inBrowserContext(task, timeoutSeconds);
                }
                public void close() { browser.close(); }
            });
        }
        String chromium = System.getProperty("argos.testChromium", "");
        return chromium.isBlank() ? Optional.empty()
                : Optional.of(new ChromiumSession(Path.of(chromium), temporary.resolve("chromium-profile")));
    }

    final class ChromiumSession implements TestBrowserSession {
        private final ExecutorService driver = Executors.newSingleThreadExecutor();
        private Playwright playwright;
        private BrowserContext context;
        private Page current;

        ChromiumSession(Path executable, Path profile) throws Exception {
            run(() -> {
                playwright = Playwright.create();
                // Like Firefox with a document: deliver PDFs as files instead of the built-in viewer.
                java.nio.file.Files.createDirectories(profile.resolve("Default"));
                java.nio.file.Files.writeString(profile.resolve("Default").resolve("Preferences"),
                        "{\"plugins\":{\"always_open_pdf_externally\":true}}");
                context = playwright.chromium().launchPersistentContext(profile,
                        new BrowserType.LaunchPersistentContextOptions()
                                .setExecutablePath(executable).setHeadless(true));
                current = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                return null;
            }, 120);
        }

        private <T> T run(java.util.concurrent.Callable<T> action, int timeoutSeconds) {
            try {
                return driver.submit(action).get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new IllegalStateException("Browser operation failed: " + error.getMessage(), error);
            }
        }

        public String engine() { return "chromium"; }

        @Override
        public boolean canCancelDownloads() { return true; }

        public void openCurrent(String url) { run(() -> current.navigate(url), 60); }

        public String currentText() { return run(() -> current.locator("body").innerText(), 60); }

        public String currentUrl() { return run(() -> current.url(), 60); }

        public <T> T inBrowserContext(Function<BrowserContext, T> task, int timeoutSeconds) {
            return run(() -> task.apply(context), timeoutSeconds);
        }

        public void close() {
            run(() -> {
                context.close();
                playwright.close();
                return null;
            }, 60);
            driver.shutdown();
        }
    }
}
