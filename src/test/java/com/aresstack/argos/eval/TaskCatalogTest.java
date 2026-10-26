package com.aresstack.argos.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskCatalogTest {
    private static EvalTask task(String id) {
        return TaskCatalog.select(id).get(0);
    }

    @Test
    void judgesAnswersAgainstExpectedAndDistractorValues() {
        assertTrue(task("search-pick").accepts("The annual report lists 212 clear nights in 2025."));
        assertFalse(task("search-pick").accepts("About 150 clear nights, the report says 212."));
        assertTrue(task("search-retry").accepts("Saturdays from 18:00 to 23:30."));
        assertTrue(task("search-retry").accepts("On Saturday it is open 6 pm – 11:30 pm."));
        assertFalse(task("search-retry").accepts("Saturday 12:00–22:00"));
        assertTrue(task("simple-form").accepts("Ticket KV-4471 is CONFIRMED, seat F12."));
        assertFalse(task("simple-form").accepts("I could not submit the form."));
    }

    @Test
    void findSiteAcceptsTheHomepageButNotSubpagesOrDistractors() {
        EvalTask findSite = task("find-site");
        assertTrue(findSite.accepts("The official website is http://www.kestrel-observatory.test:4321/"));
        assertTrue(findSite.accepts("It is http://www.kestrel-observatory.test:4321."));
        assertTrue(findSite.accepts("**http://www.kestrel\u2011observatory.test:62469/**"));
        assertTrue(findSite.accepts("[Official site](http://www.kestrel-observatory.test:4321/)"));
        assertTrue(findSite.accepts("www.kestrel-observatory.test"));
        assertFalse(findSite.accepts("http://www.kestrel-observatory.test:4321/instruments"));
        assertFalse(findSite.accepts("http://kvo-fanclub.test:4321/ (not www.kestrel-observatory.test)"));
    }

    @Test
    void theFormTaskIsNotSolvableWithTheCompactTools() {
        assertFalse(task("simple-form").solvableWith(EvalProfile.COMPACT));
        assertTrue(task("simple-form").solvableWith(EvalProfile.COMPACT_INTERACT));
        assertTrue(task("simple-form").solvableWith(EvalProfile.EXPERT));
        assertTrue(task("long-page").solvableWith(EvalProfile.COMPACT));
    }

    @Test
    void selectionRejectsUnknownTasks() {
        assertEquals(8, TaskCatalog.select("fixtures").size());
        assertEquals(3, TaskCatalog.select("live").size());
        assertEquals(11, TaskCatalog.select("all").size());
        assertEquals(2, TaskCatalog.select("find-site, long-page").size());
        assertThrows(IllegalArgumentException.class, () -> TaskCatalog.select("find-sites"));
    }
}
