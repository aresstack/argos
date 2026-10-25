package com.aresstack.argos.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the organic result region of a search page (port of AskAI's SearchPageMechanicalAnalyzer and
 * SearchPageContainerScorer). Coarse region classification removes navigation, footers, forms, ads and
 * filters; every remaining container is scored by independent signal families; too few discriminating
 * families or too little confidence yields LOW_CONFIDENCE, never a guessed container.
 */
final class ResultRegionAnalyzer {
    enum SignalFamily { DOM_SEMANTICS, LINK_STRUCTURE, TEXT_STRUCTURE, REPEATED_STRUCTURE }

    enum Region { NAVIGATION, FOOTER, SEARCH_CONTROLS, AUXILIARY, PAGINATION, ADVERTISEMENT, FILTERS, UNKNOWN }

    record Signal(SignalFamily family, String name, double score, String rationale) {
        @Override
        public String toString() {
            return family + "/" + name + "=" + Math.round(score * 100) / 100.0 + " (" + rationale + ")";
        }
    }

    record ScoredContainer(String containerId, List<Signal> signals, double totalScore) {
        ScoredContainer(String containerId, List<Signal> signals) {
            this(containerId, List.copyOf(signals), signals.stream().mapToDouble(Signal::score).sum());
        }

        Set<SignalFamily> discriminatingFamilies() {
            Set<SignalFamily> families = EnumSet.noneOf(SignalFamily.class);
            for (Signal signal : signals) if (signal.score() != 0) families.add(signal.family());
            return families;
        }
    }

    /** The resolved region; {@code organicContainerId} is empty when the layout was not understood. */
    record Resolution(String snapshotId, String organicContainerId, double confidence, boolean lowConfidence,
                      List<ScoredContainer> ranking) {
        boolean hasOrganicContainer() {
            return !organicContainerId.isEmpty();
        }
    }

    private final SearchAnalysisSettings settings;

    ResultRegionAnalyzer(SearchAnalysisSettings settings) {
        this.settings = settings;
    }

    Resolution analyze(SerpDocument document) {
        Map<String, SerpContainer> byId = document.containersById();
        List<ScoredContainer> scored = new ArrayList<>();
        for (SerpContainer container : document.containers()) {
            if (classify(container) == Region.UNKNOWN && container.visible() && container.linkCount() > 0
                    && container.textLength() >= settings.minimumContainerTextCharacters()) {
                scored.add(score(container, byId));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredContainer::totalScore).reversed());
        if (scored.size() > settings.maximumCandidateContainers()) {
            scored = new ArrayList<>(scored.subList(0, settings.maximumCandidateContainers()));
        }
        if (scored.isEmpty()) return new Resolution(document.snapshotId(), "", 0, true, scored);
        ScoredContainer best = scored.get(0);
        double confidence = confidenceOf(best);
        boolean lowConfidence = best.discriminatingFamilies().size() < settings.minimumDiscriminatingSignalFamilies()
                || confidence < settings.minimumResultStructuralConfidence();
        return new Resolution(document.snapshotId(), lowConfidence ? "" : best.containerId(), confidence,
                lowConfidence, scored);
    }

    /** Total score against the sum of the positive result-defining weights. */
    private double confidenceOf(ScoredContainer scored) {
        double strong = settings.repeatedBlockWeight() + settings.titleLinkWeight() + settings.snippetPresenceWeight()
                + settings.nonLinkTextWeight() + settings.externalLinkWeight() + settings.semanticMainWeight();
        return strong <= 0 ? 0 : Math.max(0, Math.min(1, scored.totalScore() / strong));
    }

    /** Only unambiguous markers classify; everything else stays UNKNOWN and competes. */
    static Region classify(SerpContainer c) {
        if (c.hasFlag("NAV")) return Region.NAVIGATION;
        if (c.hasFlag("FOOTER")) return Region.FOOTER;
        if (c.hasFlag("FORM")) return Region.SEARCH_CONTROLS;
        if (c.hasFlag("ASIDE")) return Region.AUXILIARY;
        List<String> tokens = nameTokens(c);
        if (containsAny(tokens, "pagination", "paging", "pager")) return Region.PAGINATION;
        if (containsAny(tokens, "ad", "ads", "advert", "sponsored", "sponsor")) return Region.ADVERTISEMENT;
        if (containsAny(tokens, "filter", "filters", "facet", "facets")) return Region.FILTERS;
        return Region.UNKNOWN;
    }

    private ScoredContainer score(SerpContainer c, Map<String, SerpContainer> byId) {
        List<Signal> signals = new ArrayList<>();
        if (c.hasFlag("MAIN")) {
            signals.add(new Signal(SignalFamily.DOM_SEMANTICS, "main", settings.semanticMainWeight(), "main/role=main"));
        }
        linkStructure(c, signals);
        textStructure(c, signals);
        int largestGroup = 0;
        for (String childId : c.childIds()) {
            SerpContainer child = byId.get(childId);
            if (child != null && child.visible()) largestGroup = Math.max(largestGroup, child.similarSiblingCount() + 1);
        }
        if (largestGroup >= settings.minimumRepeatedSiblingCount()) {
            signals.add(new Signal(SignalFamily.REPEATED_STRUCTURE, "repeatedBlocks", settings.repeatedBlockWeight(),
                    largestGroup + " structurally similar child blocks"));
        }
        return new ScoredContainer(c.id(), signals);
    }

    private void linkStructure(SerpContainer c, List<Signal> signals) {
        if (c.linkCount() == 0) return;
        if (c.externalLinks() > 0) {
            signals.add(new Signal(SignalFamily.LINK_STRUCTURE, "externalTargets",
                    settings.externalLinkWeight() * c.externalLinks() / c.linkCount(),
                    c.externalLinks() + "/" + c.linkCount() + " external"));
        }
        penalty(signals, "sameHost", c.sameHostLinks(), c.linkCount(), settings.sameHostPenalty());
        penalty(signals, "subdomain", c.subdomainLinks(), c.linkCount(), settings.subdomainPenalty());
        penalty(signals, "sameRegistrable", c.sameRegistrableLinks(), c.linkCount(),
                settings.sameRegistrableDomainPenalty());
        penalty(signals, "actionLinks", c.actionLinks(), c.linkCount(), settings.unknownDomainPenalty());
        if (c.textLength() > 0) {
            double density = (double) c.linkTextLength() / c.textLength();
            if (density > settings.maximumNavigationLinkDensity()) {
                signals.add(new Signal(SignalFamily.LINK_STRUCTURE, "linkDensity", -settings.navigationRolePenalty(),
                        "link-text density " + Math.round(density * 100) / 100.0));
            }
        }
    }

    private static void penalty(List<Signal> signals, String name, int count, int total, double weight) {
        if (count > 0) {
            signals.add(new Signal(SignalFamily.LINK_STRUCTURE, name, -weight * count / total, count + "/" + total));
        }
    }

    private void textStructure(SerpContainer c, List<Signal> signals) {
        if (c.nonLinkTextLength() >= settings.minimumNonLinkTextCharacters() && c.nonLinkTextLength() > 0) {
            double saturated = Math.min(1.0, (double) c.nonLinkTextLength() / settings.textLengthSaturationCharacters());
            signals.add(new Signal(SignalFamily.TEXT_STRUCTURE, "nonLinkText", settings.nonLinkTextWeight() * saturated,
                    c.nonLinkTextLength() + " non-link chars"));
        }
        if (c.headingCount() > 0) {
            signals.add(new Signal(SignalFamily.TEXT_STRUCTURE, "headings", settings.titleLinkWeight()
                    * Math.min(1.0, (double) c.headingCount() / settings.minimumRepeatedSiblingCount()),
                    c.headingCount() + " headings"));
        }
        if (c.paragraphCount() > 0) {
            signals.add(new Signal(SignalFamily.TEXT_STRUCTURE, "paragraphs", settings.snippetPresenceWeight()
                    * Math.min(1.0, (double) c.paragraphCount() / settings.minimumRepeatedSiblingCount()),
                    c.paragraphCount() + " paragraphs"));
        }
    }

    private static List<String> nameTokens(SerpContainer c) {
        StringBuilder names = new StringBuilder(c.elementId()).append(' ').append(c.role());
        for (String token : c.classTokens()) names.append(' ').append(token);
        // Whole tokens split on non-letters: "ad" must not match "header" or "badge".
        return List.of(names.toString().toLowerCase(Locale.ROOT).split("[^a-z]+"));
    }

    private static boolean containsAny(List<String> tokens, String... needles) {
        for (String needle : needles) if (tokens.contains(needle)) return true;
        return false;
    }
}
