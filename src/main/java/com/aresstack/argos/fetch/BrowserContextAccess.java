package com.aresstack.argos.fetch;

import com.microsoft.playwright.BrowserContext;

import java.util.function.Function;

/** Port to the running browser session for work in background tabs. Implemented by the Camoufox adapter. */
public interface BrowserContextAccess {
    /** Runs {@code task} on the browser's driver thread with the started context; the current page stays. */
    <T> T inBrowserContext(Function<BrowserContext, T> task, int timeoutSeconds);

    /** URL of the current page, or empty before the browser started. */
    String currentUrl();
}
