package com.aresstack.argos.search;

import java.util.List;

/**
 * One block-level container of a search page: structure, text and link statistics only. Ids are
 * snapshot-local ("container-0001"). Geometry and computed colours are deliberately absent: Argos
 * reads the page from serialized HTML and never measures the live DOM with page-visible scripts.
 */
record SerpContainer(
        String id, String parentId, List<String> childIds, int siblingIndex, int depth,
        String tag, String elementId, List<String> classTokens, String role, List<String> semanticFlags,
        String textExcerpt, int textLength, int linkTextLength, int headingCount, int paragraphCount,
        int linkCount, int sameHostLinks, int subdomainLinks, int sameRegistrableLinks,
        int externalLinks, int actionLinks, boolean visible, String structureSignature,
        int similarSiblingCount) {

    SerpContainer {
        childIds = List.copyOf(childIds);
        classTokens = List.copyOf(classTokens);
        semanticFlags = List.copyOf(semanticFlags);
    }

    int nonLinkTextLength() {
        return Math.max(0, textLength - linkTextLength);
    }

    boolean hasFlag(String flag) {
        return semanticFlags.contains(flag);
    }
}
