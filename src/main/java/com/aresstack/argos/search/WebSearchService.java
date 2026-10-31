package com.aresstack.argos.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs one web search across the configured engines (port of AskAI's engine-navigation loop in
 * PlaywrightBrowserSession). Engines are tried in order until one delivers organic results; an
 * engine's endpoints are fallbacks for the same provider. Per result page: extract first, and only
 * when that fails resolve a consent banner (one trusted click, never "accept all" without opt-in) and
 * look for a challenge. A challenged
 * engine family is locked for a while instead of being retried, which would only escalate the block.
 */
public final class WebSearchService {
    public enum AttemptOutcome {
        ORGANIC_RESULTS, NO_ORGANIC_RESULTS, EXTRACTION_FAILED, CHALLENGE_PENDING, CONSENT_REQUIRED, NAVIGATION_FAILED
    }

    public record Hit(int rank, String title, String url, String snippet) {
    }

    public record Attempt(String host, AttemptOutcome outcome, String detail) {
    }

    public record ChallengeNotice(String family, String url, String evidence) {
    }

    public record Report(List<Hit> hits, List<String> providerHosts, List<Attempt> attempts,
                         List<ChallengeNotice> challenges) {
        public Report {
            hits = List.copyOf(hits);
            providerHosts = List.copyOf(providerHosts);
            attempts = List.copyOf(attempts);
            challenges = List.copyOf(challenges);
        }

        /**
         * ORGANIC_RESULTS with hits; NO_ORGANIC_RESULTS only when an engine explicitly reported none;
         * otherwise EXTRACTION_FAILED: nothing readable came back, which is a failure, not "no results".
         */
        public SearchOutcome outcome() {
            if (!hits.isEmpty()) return SearchOutcome.ORGANIC_RESULTS;
            return attempts.stream().anyMatch(attempt -> attempt.outcome() == AttemptOutcome.NO_ORGANIC_RESULTS)
                    ? SearchOutcome.NO_ORGANIC_RESULTS : SearchOutcome.EXTRACTION_FAILED;
        }
    }

    /** No engine could be reached at all: a technical failure, not an empty search. */
    public static final class SearchUnavailableException extends Exception {
        public SearchUnavailableException(String message) {
            super(message);
        }
    }

    private final SearchPageBrowser browser;
    private final SearchSettings settings;
    private final SearchDocumentReader reader;
    private final SearchResultExtractor extractor;
    private final Clock clock;
    private final Map<String, Instant> lockedFamilies = new HashMap<>();
    private long generation;

    public WebSearchService(SearchPageBrowser browser, SearchSettings settings) {
        this(browser, settings, SearchLayoutResolver.NONE, Clock.systemUTC());
    }

    public WebSearchService(SearchPageBrowser browser, SearchSettings settings, SearchLayoutResolver layoutResolver,
                            Clock clock) {
        SearchAnalysisSettings analysis = SearchAnalysisSettings.defaults();
        this.browser = browser;
        this.settings = settings;
        this.reader = new SearchDocumentReader(new SerpDocumentParser(analysis));
        this.extractor = new SearchResultExtractor(analysis, layoutResolver);
        this.clock = clock;
    }

    public synchronized Report search(String query) throws SearchUnavailableException {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Empty search query");
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        List<Hit> hits = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        Set<String> providerHosts = new LinkedHashSet<>();
        List<Attempt> attempts = new ArrayList<>();
        List<ChallengeNotice> challenges = new ArrayList<>();
        boolean anyReached = false;
        for (SearchEngine engine : settings.engines()) {
            boolean delivered = false;
            for (SearchEngine.Endpoint endpoint : engine.endpoints()) {
                String family = DomainNames.registrableDomain(endpoint.host());
                if (isLocked(family)) {
                    attempts.add(new Attempt(endpoint.host(), AttemptOutcome.CHALLENGE_PENDING, "family locked"));
                    break;
                }
                SearchPageBrowser.LoadedPage page;
                try {
                    page = browser.open(endpoint.url(encoded));
                } catch (Exception unreachable) {
                    attempts.add(new Attempt(endpoint.host(), AttemptOutcome.NAVIGATION_FAILED, safeMessage(unreachable)));
                    continue;
                }
                anyReached = true;
                PageVerdict verdict = evaluate(page, query, engine, endpoint);
                String host = DomainNames.hostOf(verdict.page().url());
                if (!host.isEmpty()) providerHosts.add(host);
                if (verdict.challenge().isPresent()) {
                    String challengedFamily = DomainNames.registrableDomain(host.isEmpty() ? endpoint.host() : host);
                    lockedFamilies.put(challengedFamily, clock.instant().plus(settings.challengeLock()));
                    challenges.add(new ChallengeNotice(challengedFamily, verdict.page().url(),
                            verdict.challenge().get().evidence()));
                    attempts.add(new Attempt(host, AttemptOutcome.CHALLENGE_PENDING, verdict.challenge().get().evidence()));
                    break; // never retry a challenged provider through its other endpoints
                }
                if (verdict.consentRequired()) {
                    attempts.add(new Attempt(host, AttemptOutcome.CONSENT_REQUIRED,
                            "consent banner offers only 'accept all' (SEARCH_CONSENT_ACCEPT=true allows it)"));
                    break; // controlled stop for this provider; its other endpoints show the same wall
                }
                SearchResultExtractor.Extraction extraction = verdict.extraction();
                attempts.add(new Attempt(host, AttemptOutcome.valueOf(extraction.outcome().name()), lastDiagnostic(extraction)));
                if (extraction.outcome() == SearchOutcome.ORGANIC_RESULTS) {
                    for (SearchResultExtractor.Candidate candidate : extraction.candidates()) {
                        if (hits.size() >= settings.maxResults()) break;
                        if (seenUrls.add(candidate.url())) {
                            hits.add(new Hit(hits.size() + 1, candidate.title(), candidate.url(), candidate.snippet()));
                        }
                    }
                    delivered = true;
                    break;
                }
                if (extraction.outcome() == SearchOutcome.NO_ORGANIC_RESULTS) break; // other endpoints know no more
            }
            if (delivered) break;
        }
        if (!anyReached && attempts.stream().noneMatch(attempt -> attempt.outcome() == AttemptOutcome.CHALLENGE_PENDING)) {
            throw new SearchUnavailableException("No search engine was reachable: " + attempts.stream()
                    .map(attempt -> attempt.host() + " " + attempt.detail()).toList());
        }
        return new Report(hits, List.copyOf(providerHosts), attempts, challenges);
    }

    private record PageVerdict(SearchPageBrowser.LoadedPage page, SearchResultExtractor.Extraction extraction,
                               Optional<SearchPageGuards.Challenge> challenge, boolean consentRequired) {
        PageVerdict(SearchPageBrowser.LoadedPage page, SearchResultExtractor.Extraction extraction) {
            this(page, extraction, Optional.empty(), false);
        }
    }

    /** Extract; if that fails, resolve a consent banner once and re-read; then check for a challenge. */
    private PageVerdict evaluate(SearchPageBrowser.LoadedPage page, String query, SearchEngine engine,
                                 SearchEngine.Endpoint endpoint) {
        SearchDocumentReader.Read read = reader.read(page, endpoint, ++generation);
        SearchResultExtractor.Extraction extraction = extractor.extract(read.serp(), query, endpoint, engine.chromeDomains());
        if (extraction.outcome() == SearchOutcome.ORGANIC_RESULTS) return new PageVerdict(page, extraction);
        Optional<SearchPageGuards.ConsentControl> consent =
                SearchPageGuards.findConsentControl(read.html(), settings.acceptAllConsent());
        if (consent.isPresent()) {
            try {
                if (browser.click(consent.get().selector())) {
                    page = browser.current();
                    read = reader.read(page, endpoint, ++generation);
                    extraction = extractor.extract(read.serp(), query, endpoint, engine.chromeDomains());
                    if (extraction.outcome() == SearchOutcome.ORGANIC_RESULTS) return new PageVerdict(page, extraction);
                }
            } catch (Exception consentFailed) {
                // The page stays as it is; extraction and challenge checks below decide.
            }
        }
        Optional<SearchPageGuards.Challenge> challenge =
                SearchPageGuards.findChallenge(read.html(), page.url(), read.serp().visibleText());
        boolean consentRequired = challenge.isEmpty() && consent.isEmpty()
                && SearchPageGuards.offersOnlyAcceptance(read.html());
        return new PageVerdict(page, extraction, challenge, consentRequired);
    }

    private boolean isLocked(String family) {
        Instant until = lockedFamilies.get(family);
        if (until == null) return false;
        if (clock.instant().isBefore(until)) return true;
        lockedFamilies.remove(family);
        return false;
    }

    private static String lastDiagnostic(SearchResultExtractor.Extraction extraction) {
        List<String> diagnostics = extraction.diagnostics();
        String last = diagnostics.isEmpty() ? "" : diagnostics.get(diagnostics.size() - 1);
        return last.length() > 200 ? last.substring(0, 200) : last;
    }

    /** First line of the error, bounded; Playwright messages may carry call logs. */
    static String safeMessage(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        int newline = message.indexOf('\n');
        if (newline >= 0) message = message.substring(0, newline);
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    /** Parses a loaded page once into the jsoup tree (for guards) and the SERP model (for extraction). */
    static final class SearchDocumentReader {
        record Read(Document html, SerpDocument serp) {
        }

        private final SerpDocumentParser parser;

        SearchDocumentReader(SerpDocumentParser parser) {
            this.parser = parser;
        }

        Read read(SearchPageBrowser.LoadedPage page, SearchEngine.Endpoint endpoint, long generation) {
            Document html = Jsoup.parse(page.html() == null ? "" : page.html(), page.url() == null ? "" : page.url());
            return new Read(html, parser.parse(html, page.url(), page.title(), endpoint.resultBlockSelector(), generation));
        }
    }
}
