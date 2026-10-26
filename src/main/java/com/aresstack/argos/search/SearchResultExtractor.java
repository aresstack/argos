package com.aresstack.argos.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The extraction pipeline for one result page (port of AskAI's LegacySearchResultExtractor):
 * engine-adapter blocks first, then the mechanical region analysis, then the optional AI layout
 * resolver, then a bounded link harvest. Outcomes are honest: an ununderstood layout is
 * EXTRACTION_FAILED, only an explicit "no results" page is NO_ORGANIC_RESULTS.
 */
final class SearchResultExtractor {
    record Candidate(int rank, String title, String url, String snippet, String displayedDomain) {
    }

    record Extraction(SearchOutcome outcome, List<Candidate> candidates, double confidence, List<String> diagnostics) {
        Extraction {
            candidates = List.copyOf(candidates);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final SearchAnalysisSettings settings;
    private final ResultRegionAnalyzer analyzer;
    private final ResultBlockDetector detector;
    private final SearchLayoutResolver layoutResolver;

    SearchResultExtractor(SearchAnalysisSettings settings, SearchLayoutResolver layoutResolver) {
        this.settings = settings;
        this.analyzer = new ResultRegionAnalyzer(settings);
        this.detector = new ResultBlockDetector(settings);
        this.layoutResolver = layoutResolver == null ? SearchLayoutResolver.NONE : layoutResolver;
    }

    Extraction extract(SerpDocument document, String query, SearchEngine.Endpoint endpoint, Set<String> chromeDomains) {
        Extraction structured = extractStructured(document, query);
        return withLinkHarvest(document, structured, endpoint.harvestWhenUnstructured(), chromeDomains);
    }

    private Extraction extractStructured(SerpDocument document, String query) {
        List<String> diagnostics = new ArrayList<>();
        if (document.truncated()) diagnostics.add("page capture truncated by limits");
        if (!document.adapterBlockIds().isEmpty()) {
            ResultBlockDetector.Detection named = detector.detectNamed(document, document.adapterBlockIds());
            if (!named.blocks().isEmpty()) {
                diagnostics.add("engine adapter recognised " + document.adapterBlockIds().size() + " result blocks");
                return assemble(named, 1.0, diagnostics);
            }
            diagnostics.add("engine adapter blocks yielded no result; falling back to mechanical analysis");
        }
        ResultRegionAnalyzer.Resolution resolution = analyzer.analyze(document);
        if (resolution.lowConfidence()) {
            if (showsExplicitNoResults(document)) {
                diagnostics.add("explicit no-results indication");
                return new Extraction(SearchOutcome.NO_ORGANIC_RESULTS, List.of(), resolution.confidence(), diagnostics);
            }
            Optional<Extraction> repaired = resolveWithAi(document, resolution, query, diagnostics);
            if (repaired.isPresent()) return repaired.get();
            diagnostics.add("layout LOW_CONFIDENCE (" + round(resolution.confidence())
                    + "): not understood, which is not the same as an empty result page");
            return failed(resolution.confidence(), diagnostics);
        }
        ResultBlockDetector.Detection detection = detector.detect(document, resolution);
        diagnostics.addAll(detection.rejections());
        if (detection.blocks().isEmpty()) {
            if (showsExplicitNoResults(document)) {
                diagnostics.add("explicit no-results indication");
                return new Extraction(SearchOutcome.NO_ORGANIC_RESULTS, List.of(), resolution.confidence(), diagnostics);
            }
            // A region without extractable blocks is just as ununderstood as a low-confidence one.
            Optional<Extraction> repaired = resolveWithAi(document, resolution, query, diagnostics);
            if (repaired.isPresent()) return repaired.get();
            diagnostics.add("a result region was resolved but no valid result block emerged");
            return failed(resolution.confidence(), diagnostics);
        }
        return assemble(detection, resolution.confidence(), diagnostics);
    }

    /** Offers the ununderstood layout to the AI port and applies only a validated decision. */
    private Optional<Extraction> resolveWithAi(SerpDocument document, ResultRegionAnalyzer.Resolution resolution,
                                               String query, List<String> diagnostics) {
        if (layoutResolver == SearchLayoutResolver.NONE || resolution.ranking().isEmpty()) return Optional.empty();
        Map<String, SerpContainer> byId = document.containersById();
        List<SearchLayoutResolver.Candidate> candidates = new ArrayList<>();
        for (ResultRegionAnalyzer.ScoredContainer scored : resolution.ranking()) {
            SerpContainer c = byId.get(scored.containerId());
            candidates.add(new SearchLayoutResolver.Candidate(c.id(), describe(c)));
        }
        Optional<SearchLayoutResolver.Decision> decision;
        try {
            decision = layoutResolver.resolve(new SearchLayoutResolver.Request(query, document.url(),
                    document.snapshotId(), candidates));
        } catch (RuntimeException resolverFailed) {
            diagnostics.add("AI layout resolver failed: " + resolverFailed.getClass().getSimpleName());
            return Optional.empty();
        }
        if (decision.isEmpty()) return Optional.empty();
        String violation = validate(decision.get(), document, candidates);
        if (violation != null) {
            diagnostics.add("AI layout decision rejected: " + violation);
            return Optional.empty();
        }
        SearchLayoutResolver.Decision valid = decision.get();
        ResultBlockDetector.Detection detection = valid.resultBlockIds().isEmpty()
                ? detector.detect(document, new ResultRegionAnalyzer.Resolution(document.snapshotId(),
                        valid.organicContainerId(), valid.confidence(), false, resolution.ranking()))
                : detector.detectNamed(document, valid.resultBlockIds());
        diagnostics.addAll(detection.rejections());
        if (detection.blocks().isEmpty()) {
            diagnostics.add("AI-resolved layout produced no valid result block");
            return Optional.of(failed(valid.confidence(), diagnostics));
        }
        diagnostics.add("layout resolved by AI port");
        return Optional.of(assemble(detection, valid.confidence(), diagnostics));
    }

    /** @return a violation message, or null when the decision only references offered, nested ids. */
    static String validate(SearchLayoutResolver.Decision decision, SerpDocument document,
                           List<SearchLayoutResolver.Candidate> offered) {
        if (!document.snapshotId().equals(decision.snapshotId())) return "stale snapshot " + decision.snapshotId();
        if (offered.stream().noneMatch(c -> c.containerId().equals(decision.organicContainerId()))) {
            return "unknown organic container id " + decision.organicContainerId();
        }
        Map<String, SerpContainer> byId = document.containersById();
        for (String blockId : decision.resultBlockIds()) {
            SerpContainer current = byId.get(blockId);
            while (current != null && !current.id().equals(decision.organicContainerId())) {
                current = byId.get(current.parentId());
            }
            if (current == null) return "block " + blockId + " is outside the organic region";
        }
        return null;
    }

    private static String describe(SerpContainer c) {
        String excerpt = c.textExcerpt().length() > 160 ? c.textExcerpt().substring(0, 160) : c.textExcerpt();
        return c.tag() + (c.elementId().isEmpty() ? "" : "#" + c.elementId())
                + (c.classTokens().isEmpty() ? "" : "." + String.join(".", c.classTokens()))
                + " links=" + c.linkCount() + " external=" + c.externalLinks() + " headings=" + c.headingCount()
                + " children=" + c.childIds().size() + " text=\"" + excerpt + "\"";
    }

    private Extraction assemble(ResultBlockDetector.Detection detection, double confidence, List<String> diagnostics) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResultBlockDetector.Block block : detection.blocks()) {
            if (!seen.add(block.primaryLink().targetUrl())) continue; // nested adapter matches, repeated hits
            candidates.add(new Candidate(candidates.size() + 1, block.title(), block.primaryLink().targetUrl(),
                    block.snippet(), block.displayedDomain()));
        }
        if (candidates.isEmpty()) {
            diagnostics.add("all detected blocks were duplicates");
            return failed(confidence, diagnostics);
        }
        return new Extraction(SearchOutcome.ORGANIC_RESULTS, candidates, confidence, diagnostics);
    }

    /**
     * Tops up a thin structured answer with the page's external links. Unlike AskAI (which has a
     * mandatory reranker behind it), a page whose layout was NOT understood is only harvested when the
     * endpoint is known to list results as bare links (DuckDuckGo Lite): junk links must not reach a
     * small model as "results".
     */
    private Extraction withLinkHarvest(SerpDocument document, Extraction structured, boolean harvestUnstructured,
                                       Set<String> chromeDomains) {
        boolean thin = structured.outcome() == SearchOutcome.ORGANIC_RESULTS
                && structured.candidates().size() < settings.linkHarvestMinimumStructuredCandidates();
        boolean unstructured = structured.outcome() == SearchOutcome.EXTRACTION_FAILED && harvestUnstructured;
        if (!thin && !unstructured) return structured;
        List<Candidate> combined = new ArrayList<>(structured.candidates());
        Set<String> seen = new HashSet<>();
        for (Candidate candidate : combined) seen.add(candidate.url());
        Map<String, SerpContainer> byId = document.containersById();
        for (SerpLink link : document.links()) {
            if (combined.size() >= settings.linkHarvestMaximumCandidates()) break;
            if (!link.visible() || link.relation() != DomainNames.DomainRelation.EXTERNAL) continue;
            if (insideClassifiedRegion(link.containerId(), byId)) continue; // ads, navigation, footer
            String host = DomainNames.hostOf(link.targetUrl());
            if (chromeDomains.stream().anyMatch(domain -> DomainNames.isWithin(host, domain))) continue;
            String title = link.text().isBlank() ? link.nearestHeading() : link.text();
            if (title.trim().length() < settings.minimumTitleCharacters() || !seen.add(link.targetUrl())) continue;
            String snippet = SerpDocumentParser.clean(link.surroundingText().replace(title, " "));
            combined.add(new Candidate(combined.size() + 1, title.trim(), link.targetUrl(),
                    snippet.length() > settings.maximumSnippetCharacters()
                            ? snippet.substring(0, settings.maximumSnippetCharacters()) : snippet,
                    link.displayedDomain()));
        }
        List<String> diagnostics = new ArrayList<>(structured.diagnostics());
        if (combined.size() == structured.candidates().size()) {
            diagnostics.add("link harvest found no additional external links");
            return new Extraction(structured.outcome(), structured.candidates(), structured.confidence(), diagnostics);
        }
        diagnostics.add("link harvest added " + (combined.size() - structured.candidates().size()) + " external links");
        return new Extraction(SearchOutcome.ORGANIC_RESULTS, combined, structured.confidence(), diagnostics);
    }

    private boolean insideClassifiedRegion(String containerId, Map<String, SerpContainer> byId) {
        SerpContainer container = byId.get(containerId);
        return container != null && ResultRegionAnalyzer.withinExcludedRegion(container, byId);
    }

    private boolean showsExplicitNoResults(SerpDocument document) {
        for (SerpContainer container : document.containers()) {
            if (!container.visible()) continue;
            String excerpt = container.textExcerpt().toLowerCase(Locale.ROOT);
            for (String marker : settings.noResultsTexts()) {
                if (!marker.isEmpty() && excerpt.contains(marker)) return true;
            }
        }
        return false;
    }

    private static Extraction failed(double confidence, List<String> diagnostics) {
        return new Extraction(SearchOutcome.EXTRACTION_FAILED, List.of(), confidence, diagnostics);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
