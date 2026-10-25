package com.aresstack.argos.search;

import java.util.List;

/**
 * Thresholds and weights of the mechanical SERP analysis. Defaults are AskAI's tested values
 * ({@code LegacyBrowserSearchDefaults.analysis()/extraction()}); the geometry and colour weights are
 * not carried over because Argos does not measure the live DOM.
 */
record SearchAnalysisSettings(
        List<String> noResultsTexts,
        int maximumCandidateContainers,
        int minimumContainerTextCharacters,
        int minimumNonLinkTextCharacters,
        int minimumRepeatedSiblingCount,
        double minimumResultStructuralConfidence,
        double maximumNavigationLinkDensity,
        double externalLinkWeight,
        double sameHostPenalty,
        double sameRegistrableDomainPenalty,
        double subdomainPenalty,
        double unknownDomainPenalty,
        double repeatedBlockWeight,
        double nonLinkTextWeight,
        double titleLinkWeight,
        double snippetPresenceWeight,
        double headingLinkWeight,
        double semanticMainWeight,
        double navigationRolePenalty,
        double resultBlockSimilarityThreshold,
        int minimumDiscriminatingSignalFamilies,
        int textLengthSaturationCharacters,
        int maximumContainerDomDepth,
        int maximumCapturedContainers,
        int maximumLinksPerContainer,
        int maximumStructureSignatureDepth,
        int linkHarvestMinimumStructuredCandidates,
        int linkHarvestMaximumCandidates,
        int minimumTitleCharacters,
        int minimumSnippetCharacters,
        int maximumSnippetCharacters,
        int maximumExtractedCandidates,
        int maximumSiteLinksPerResult,
        double minimumPrimaryLinkConfidence) {

    SearchAnalysisSettings {
        noResultsTexts = List.copyOf(noResultsTexts);
    }

    static SearchAnalysisSettings defaults() {
        return new SearchAnalysisSettings(
                List.of("no results", "keine ergebnisse", "did not match any", "nichts gefunden",
                        "no matches", "0 results", "keine treffer", "there are no results for",
                        "es gibt keine ergebnisse", "no results found for", "ergab keine treffer"),
                24,     // maximumCandidateContainers
                80,     // minimumContainerTextCharacters
                24,     // minimumNonLinkTextCharacters
                3,      // minimumRepeatedSiblingCount
                // minimumResultStructuralConfidence: AskAI uses 0.5 with geometry and colour signals
                // adding up to ~2 points; without them the same pages land at 0.34-0.6 (fixtures).
                0.3,
                0.65,   // maximumNavigationLinkDensity
                1.0,    // externalLinkWeight
                0.6,    // sameHostPenalty
                0.3,    // sameRegistrableDomainPenalty
                0.4,    // subdomainPenalty
                0.2,    // unknownDomainPenalty
                1.2,    // repeatedBlockWeight: repeated sibling blocks are the strongest signal
                0.8,    // nonLinkTextWeight
                1.0,    // titleLinkWeight
                0.9,    // snippetPresenceWeight
                0.6,    // headingLinkWeight
                0.8,    // semanticMainWeight
                1.0,    // navigationRolePenalty
                0.6,    // resultBlockSimilarityThreshold
                1,      // minimumDiscriminatingSignalFamilies
                600,    // textLengthSaturationCharacters
                40,     // maximumContainerDomDepth
                6_000,  // maximumCapturedContainers: Java-side parsing affords whole SERPs
                50,     // maximumLinksPerContainer
                3,      // maximumStructureSignatureDepth
                5,      // linkHarvestMinimumStructuredCandidates
                20,     // linkHarvestMaximumCandidates
                3,      // minimumTitleCharacters
                0,      // minimumSnippetCharacters
                400,    // maximumSnippetCharacters
                40,     // maximumExtractedCandidates
                4,      // maximumSiteLinksPerResult
                0.5);   // minimumPrimaryLinkConfidence
    }
}
