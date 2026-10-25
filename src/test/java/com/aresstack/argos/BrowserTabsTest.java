package com.aresstack.argos;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserTabsTest {
    @Test void assignsStableIdsAndKeepsTheFirstTabCurrent() {
        BrowserTabs tabs = new BrowserTabs();
        Page first = mock(Page.class);
        Page second = mock(Page.class);
        assertEquals("t1", tabs.adopt(first));
        assertEquals("t2", tabs.adopt(second));
        assertEquals("t1", tabs.adopt(first), "adopting twice keeps the id");
        assertEquals("t1", tabs.currentId());
        assertSame(second, tabs.select("2"));
        assertEquals("t2", tabs.currentId());
        assertEquals(List.of("t1", "t2"), List.copyOf(tabs.all().keySet()));
    }

    @Test void closingTheCurrentTabSelectsTheNewestRemainingOne() {
        BrowserTabs tabs = new BrowserTabs();
        Page first = mock(Page.class);
        Page second = mock(Page.class);
        Page third = mock(Page.class);
        tabs.adopt(first);
        tabs.adopt(second);
        tabs.adopt(third);
        tabs.select("t3");
        when(third.isClosed()).thenReturn(true);
        assertEquals("t2", tabs.currentId());
        assertEquals(2, tabs.size());
        assertEquals("t4", tabs.adopt(mock(Page.class)), "ids are never reused");
    }

    @Test void unknownTabsAreReportedWithTheOpenOnes() {
        BrowserTabs tabs = new BrowserTabs();
        tabs.adopt(mock(Page.class));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> tabs.select("t9"));
        assertTrue(error.getMessage().contains("[t1]"));
    }
}
