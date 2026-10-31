package com.aresstack.argos;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FreeTabsTest {
    private final BrowserContext context = mock(BrowserContext.class);
    private final Map<Page, Integer> navigations = new HashMap<>();
    private final FreeTabs free = new FreeTabs();

    private Page blank(BrowserContext owner) {
        Page page = mock(Page.class);
        when(page.context()).thenReturn(owner);
        when(page.url()).thenReturn("about:blank");
        navigations.put(page, 0);
        return page;
    }

    private Optional<Page> claim() {
        return free.claim(context, navigations::get);
    }

    @Test void aFreeTabIsHandedOutOnce() {
        Page start = blank(context);
        free.offer(start, 0);
        assertSame(start, claim().orElseThrow());
        assertTrue(claim().isEmpty(), "parallel loads after the first open their own tabs");
    }

    @Test void aTabThatNavigatedOrShowsAPageIsNeverHandedOut() {
        Page typedInto = blank(context);
        Page loaded = blank(context);
        free.offer(typedInto, 0);
        free.offer(loaded, 0);
        navigations.put(typedInto, 1); // the user loaded something and came back to about:blank, say
        when(loaded.url()).thenReturn("https://example.org/");
        assertTrue(claim().isEmpty());
        when(loaded.url()).thenReturn("about:blank");
        assertTrue(claim().isEmpty(), "a used tab is forgotten for good");
    }

    @Test void closedWithdrawnAndOtherContextsTabsAreSkipped() {
        Page closed = blank(context);
        when(closed.isClosed()).thenReturn(true);
        Page withdrawn = blank(context);
        BrowserContext other = mock(BrowserContext.class);
        Page foreign = blank(other);
        Page usable = blank(context);
        free.offer(closed, 0);
        free.offer(withdrawn, 0);
        free.offer(foreign, 0);
        free.offer(usable, 0);
        free.withdraw(withdrawn);
        assertSame(usable, claim().orElseThrow());
        assertSame(foreign, free.claim(other, navigations::get).orElseThrow(), "kept for its own context");
    }

    @Test void clearForgetsEverything() {
        free.offer(blank(context), 0);
        free.clear();
        assertTrue(claim().isEmpty());
    }
}
