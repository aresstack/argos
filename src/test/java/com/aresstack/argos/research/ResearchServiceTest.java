package com.aresstack.argos.research;

import com.aresstack.argos.web.SearchResults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.aresstack.argos.research.ResearchWorld.hit;
import static com.aresstack.argos.research.ResearchWorld.link;
import static org.junit.jupiter.api.Assertions.*;

class ResearchServiceTest {
    private static final String QUESTION = "camoufox fingerprint rotation";
    private static final String ON_TOPIC = "Camoufox rotates its fingerprint per session. Fingerprint rotation "
            + "keeps the browser consistent. ";

    private final ResearchWorld world = new ResearchWorld();

    private static ResearchBudget budget(int pages, int minSources, int minSites, int depth) {
        return new ResearchBudget(2, pages, 5, 3, Duration.ofSeconds(120), minSources, minSites, 3, depth);
    }

    @Test
    void stopsWithSufficientEvidenceOnceEnoughSourcesFromEnoughSitesAreAccepted() throws Exception {
        world.hits(QUESTION,
                        hit("Camoufox fingerprint rotation", "https://a.example/one", "rotation of fingerprints"),
                        hit("Fingerprint rotation guide", "https://b.example/two", "camoufox"),
                        hit("Camoufox docs", "https://c.example/three", "fingerprint rotation"),
                        hit("Camoufox more", "https://d.example/four", "fingerprint rotation"))
                .page("https://a.example/one", "Camoufox fingerprint rotation", ON_TOPIC)
                .page("https://b.example/two", "Rotation", ON_TOPIC + "Guide B.")
                .page("https://c.example/three", "Docs", ON_TOPIC + "Docs C.");

        ResearchReport report = world.service(budget(8, 3, 2, 0)).research(QUESTION, null);

        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, report.stopReason());
        assertEquals(3, report.sources().size());
        assertEquals(List.of("https://a.example/one", "https://b.example/two", "https://c.example/three"), world.read);
        String text = report.format();
        assertTrue(text.startsWith("Research: " + QUESTION + "\nStopped: SUFFICIENT_EVIDENCE (3 sources from 3 sites"));
        assertTrue(text.contains("1: Camoufox fingerprint rotation — https://a.example/one\n   Camoufox rotates"));
        assertTrue(text.contains("NEXT: web_open"));
    }

    @Test
    void followsOnlyRelevantAnchorsAndNeverExpandsAnIrrelevantPage() throws Exception {
        world.hits(QUESTION,
                        hit("Camoufox fingerprint rotation", "https://a.example/start", "rotation"),
                        hit("Unrelated noodle recipe", "https://noodles.example/", "fingerprint camoufox rotation"))
                .page("https://a.example/start", "Camoufox fingerprint rotation", ON_TOPIC,
                        link("Imprint", "https://a.example/imprint"),
                        link("How Camoufox fingerprint rotation works", "https://b.example/deep"))
                .page("https://noodles.example/", "Noodles", "Boil water, add noodles.",
                        link("Camoufox fingerprint rotation explained", "https://c.example/bridge"))
                .page("https://b.example/deep", "Deep dive", ON_TOPIC + "Deep.");

        ResearchReport report = world.service(budget(8, 2, 2, 1)).research(QUESTION, null);

        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, report.stopReason());
        assertEquals(List.of("https://a.example/start", "https://noodles.example/", "https://b.example/deep"), world.read);
        assertEquals("https://a.example/start", report.sources().get(1).foundVia());
        assertEquals(1, report.progress().rejectedPages());
    }

    @Test
    void readsNoPageTwiceAcrossRedirectsCanonicalFormsAndMirrors() throws Exception {
        world.hits(QUESTION,
                        hit("Camoufox fingerprint rotation", "https://a.example/page?utm_source=x", "x"),
                        hit("Camoufox fingerprint rotation", "http://www.a.example/page/", "x"),
                        hit("Camoufox fingerprint rotation", "https://short.example/r", "x"),
                        hit("Camoufox fingerprint rotation", "https://mirror.example/copy", "x"))
                .page("https://a.example/page?utm_source=x", "Camoufox fingerprint rotation", ON_TOPIC)
                .page("https://mirror.example/copy", "Camoufox fingerprint rotation", ON_TOPIC);
        world.pages.put("https://short.example/r", new ResearchPage("https://a.example/page", "Camoufox",
                200, ON_TOPIC, List.of(), ResearchPage.Access.READABLE));

        ResearchReport report = world.service(budget(8, 3, 2, 0)).research(QUESTION, null);

        assertEquals(List.of("https://a.example/page?utm_source=x", "https://short.example/r",
                "https://mirror.example/copy"), world.read);
        assertEquals(1, report.sources().size());
        assertEquals(2, report.progress().duplicates());
        assertEquals(ResearchStopReason.NO_RELEVANT_PATHS, report.stopReason());
    }

    @Test
    void neverReopensABlockedSiteFamilyAndTreatsSearchEnginePagesAsTransit() throws Exception {
        world.hits(QUESTION,
                        hit("Camoufox fingerprint rotation", "https://www.bing.com/videos?q=camoufox", "x"),
                        hit("Camoufox fingerprint rotation", "https://blocked.example/a", "x"),
                        hit("Camoufox fingerprint rotation", "https://news.blocked.example/b", "x"),
                        hit("Camoufox fingerprint rotation", "https://open.example/c", "x"))
                .page("https://open.example/c", "Camoufox fingerprint rotation", ON_TOPIC);
        world.pages.put("https://blocked.example/a", new ResearchPage("https://blocked.example/a", "Forbidden",
                403, "Forbidden", List.of(), ResearchPage.Access.BLOCKED));

        ResearchReport report = world.service(budget(8, 1, 1, 0)).research(QUESTION, null);

        assertEquals(List.of("https://blocked.example/a", "https://open.example/c"), world.read);
        assertTrue(report.notes().contains("BLOCKED: blocked.example BLOCKED (HTTP 403), site skipped"));
        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, report.stopReason());
    }

    @Test
    void endsTypedWhenThePageBudgetIsSpent() throws Exception {
        world.hits(QUESTION, hit("Camoufox fingerprint rotation", "https://a.example/1", "x"),
                hit("Camoufox fingerprint rotation", "https://b.example/2", "x"),
                hit("Camoufox fingerprint rotation", "https://c.example/3", "x"));
        world.page("https://a.example/1", "Off", "nothing here").page("https://b.example/2", "Off", "nothing");

        ResearchReport report = world.service(budget(2, 1, 1, 0)).research(QUESTION, null);

        assertEquals(ResearchStopReason.PAGE_BUDGET_EXHAUSTED, report.stopReason());
        assertEquals(2, world.read.size());
        assertTrue(report.format().contains("NEXT: Partial result"));
    }

    @Test
    void endsTypedWhenTheTimeBudgetIsSpent() throws Exception {
        world.hits(QUESTION, hit("Camoufox fingerprint rotation", "https://a.example/1", "x"),
                hit("Camoufox fingerprint rotation", "https://b.example/2", "x"));
        world.page("https://a.example/1", "Off", "nothing here");
        world.millisPerRead = 200_000;

        ResearchReport report = world.service(budget(8, 1, 1, 0)).research(QUESTION, null);

        assertEquals(ResearchStopReason.TIME_BUDGET_EXHAUSTED, report.stopReason());
        assertEquals(1, world.read.size());
    }

    @Test
    void endsTypedAfterConsecutiveErrorsWithoutLeakingMoreThanOneShortLine() throws Exception {
        world.hits(QUESTION, hit("Camoufox fingerprint rotation", "https://a.example/1", "x"),
                hit("Camoufox fingerprint rotation", "https://b.example/2", "x"),
                hit("Camoufox fingerprint rotation", "https://c.example/3", "x"),
                hit("Camoufox fingerprint rotation", "https://d.example/4", "x"));
        world.failures.put("https://a.example/1", new IllegalStateException("x".repeat(500)));
        world.failures.put("https://b.example/2",
                new IllegalStateException("Timeout navigating to \"https://b.example/2?token=s3cret\""));

        ResearchReport report = world.service(budget(8, 1, 1, 0)).research(QUESTION, null);

        assertEquals(ResearchStopReason.ERROR_BUDGET_EXHAUSTED, report.stopReason());
        assertEquals(3, world.read.size());
        assertTrue(report.notes().stream().allMatch(note -> note.length() < 200));
        assertTrue(report.notes().contains("ERROR: b.example Timeout navigating to \"https://b.example/2?…\""));
        assertFalse(report.format().contains("s3cret"));
    }

    @Test
    void distinguishesTechnicalSearchFailureFromEmptySearchAndChallenge() throws Exception {
        world.failures.put("search:" + QUESTION, new Exception("no engine reachable"));
        assertEquals(ResearchStopReason.SEARCH_FAILED,
                world.service(budget(8, 1, 1, 0)).research(QUESTION, null).stopReason());

        world.failures.clear();
        assertEquals(ResearchStopReason.NO_SEARCH_RESULTS,
                world.service(budget(8, 1, 1, 0)).research(QUESTION, null).stopReason());

        world.searches.put(QUESTION, new SearchResults(List.of(),
                List.of("CHALLENGE: bing.com https://www.bing.com/search (solve it)", "ATTEMPT: www.bing.com CHALLENGE_PENDING")));
        ResearchReport challenged = world.service(budget(8, 1, 1, 0)).research(QUESTION, null);
        assertEquals(ResearchStopReason.CHALLENGE_REQUIRED, challenged.stopReason());
        assertTrue(challenged.format().contains("Solve it in the Argos browser window"));
        assertTrue(world.read.isEmpty());
    }

    @Test
    void listsDocumentsInsteadOfOpeningThem() throws Exception {
        world.hits(QUESTION, hit("Camoufox fingerprint rotation paper", "https://a.example/paper.pdf", "pdf"),
                        hit("Camoufox fingerprint rotation", "https://b.example/page", "x"))
                .page("https://b.example/page", "Camoufox fingerprint rotation", ON_TOPIC,
                        link("Camoufox fingerprint rotation slides", "https://b.example/slides.pptx"));

        ResearchReport report = world.service(budget(8, 2, 2, 1)).research(QUESTION, null);

        assertEquals(List.of("https://b.example/page"), world.read);
        assertEquals(List.of("https://a.example/paper.pdf", "https://b.example/slides.pptx"), report.documents());
        assertTrue(report.format().contains("Documents (not opened):\n- https://a.example/paper.pdf"));
    }

    @Test
    void plannedQueriesAreBoundedAndAPlannerFailureFallsBackToTheQuestion() throws Exception {
        ResearchService planned = new ResearchService(world, world, new LexicalRelevanceScorer(),
                question -> List.of("q one camoufox", "q two camoufox", "q three camoufox"),
                budget(8, 1, 1, 0), () -> world.now);
        planned.research(QUESTION, null);
        assertEquals(List.of("q one camoufox", "q two camoufox"), world.queried);

        world.queried.clear();
        ResearchService failing = new ResearchService(world, world, new LexicalRelevanceScorer(),
                question -> { throw new IllegalStateException("model offline"); }, budget(8, 1, 1, 0), () -> world.now);
        ResearchReport report = failing.research(QUESTION, null);
        assertEquals(List.of(QUESTION), world.queried);
        assertTrue(report.notes().get(0).startsWith("PLAN: query planning failed model offline"));
    }

    @Test
    void stopEndsTheRunningResearchAtTheNextGateAndKeepsWhatItFound() throws Exception {
        world.hits(QUESTION, hit("Camoufox fingerprint rotation", "https://a.example/1", "x"),
                        hit("Camoufox fingerprint rotation", "https://b.example/2", "x"))
                .page("https://a.example/1", "Camoufox fingerprint rotation", ON_TOPIC)
                .page("https://b.example/2", "Camoufox fingerprint rotation", ON_TOPIC + "B");
        ResearchService service = world.service(budget(8, 3, 2, 0));
        world.onRead = url -> service.stop();

        ResearchReport report = service.research(QUESTION, null);

        assertEquals(ResearchStopReason.CANCELLED, report.stopReason());
        assertEquals(1, report.sources().size());
        assertFalse(service.stop(), "nothing is running any more");
    }

    @Test
    void refusesASecondConcurrentRun() throws Exception {
        world.hits(QUESTION, hit("Camoufox fingerprint rotation", "https://a.example/1", "x"))
                .page("https://a.example/1", "Camoufox fingerprint rotation", ON_TOPIC);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        world.onRead = url -> {
            reading.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ResearchService service = world.service(budget(8, 1, 1, 0));
        AtomicReference<ResearchReport> first = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                first.set(service.research(QUESTION, null));
            } catch (Exception ignored) {
            }
        });
        runner.start();
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        assertThrows(ResearchService.ResearchBusyException.class, () -> service.research(QUESTION, null));
        release.countDown();
        runner.join(5_000);
        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, first.get().stopReason());
    }

    @Test
    void reportsProgressAndSurvivesAFailingListener() throws Exception {
        world.hits(QUESTION, hit("Camoufox fingerprint rotation", "https://a.example/1", "x"))
                .page("https://a.example/1", "Camoufox fingerprint rotation", ON_TOPIC);
        List<String> activities = new java.util.ArrayList<>();
        ResearchReport report = world.service(budget(8, 1, 1, 0)).research(QUESTION, (progress, activity) -> {
            activities.add(activity);
            throw new IllegalStateException("client went away");
        });
        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, report.stopReason());
        assertEquals(List.of("searching: " + QUESTION, "reading: https://a.example/1",
                "accepted: https://a.example/1", "stopped: SUFFICIENT_EVIDENCE"), activities);
    }

    @Test
    void rejectsAQuestionWithoutSearchableWords() {
        assertThrows(IllegalArgumentException.class, () -> world.service(ResearchBudget.defaults()).research("a ?", null));
    }
}
