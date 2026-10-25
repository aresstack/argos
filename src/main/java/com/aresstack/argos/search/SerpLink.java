package com.aresstack.argos.search;

/**
 * One anchor of a search page. The raw (possibly wrapped) href is provenance only; the resolved
 * target is what a result will point to.
 */
record SerpLink(
        String id, String containerId, String rawHref, String targetUrl, SearchRedirects.Status redirect,
        String text, String surroundingText, String nearestHeading, String displayedDomain,
        DomainNames.DomainRelation relation, boolean visible, boolean insideHeading) {
}
