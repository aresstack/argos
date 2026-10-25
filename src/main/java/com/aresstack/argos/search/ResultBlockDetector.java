package com.aresstack.argos.search;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects the repeated result blocks inside the organic region and resolves each block's primary title
 * link and snippet (port of AskAI's SearchResultBlockDetector, DomStructureClusterer,
 * PrimaryResultLinkResolver and SnippetExtractor). A single anchor is never a result; rejected blocks
 * leave a reason in the diagnostics.
 */
final class ResultBlockDetector {
    record Block(String containerId, int rank, SerpLink primaryLink, String title, String snippet,
                 String displayedDomain, List<SerpLink> siteLinks) {
    }

    record Detection(List<Block> blocks, List<String> rejections) {
        Detection {
            blocks = List.copyOf(blocks);
            rejections = List.copyOf(rejections);
        }
    }

    private final SearchAnalysisSettings settings;

    ResultBlockDetector(SearchAnalysisSettings settings) {
        this.settings = settings;
    }

    /** Mechanical discovery: descend single-child wrappers, cluster siblings, take the largest repeated cluster. */
    Detection detect(SerpDocument document, ResultRegionAnalyzer.Resolution resolution) {
        List<String> reasons = new ArrayList<>();
        if (!resolution.hasOrganicContainer() || !document.snapshotId().equals(resolution.snapshotId())) {
            reasons.add(resolution.hasOrganicContainer() ? "stale resolution" : "no organic results container");
            return new Detection(List.of(), reasons);
        }
        Map<String, SerpContainer> byId = document.containersById();
        SerpContainer container = byId.get(resolution.organicContainerId());
        while (container != null && container.childIds().size() == 1) {
            container = byId.get(container.childIds().get(0));
        }
        if (container != null && ResultRegionAnalyzer.withinExcludedRegion(container, byId)) {
            reasons.add(container.id() + ": result region lies inside an excluded region (ads, navigation, ...)");
            return new Detection(List.of(), reasons);
        }
        if (container == null || container.childIds().isEmpty()) {
            reasons.add("organic container has no child blocks");
            return new Detection(List.of(), reasons);
        }
        List<SerpContainer> children = new ArrayList<>();
        for (String childId : container.childIds()) {
            SerpContainer child = byId.get(childId);
            if (child == null || !child.visible()) continue;
            ResultRegionAnalyzer.Region region = ResultRegionAnalyzer.classify(child);
            if (region == ResultRegionAnalyzer.Region.UNKNOWN) children.add(child);
            else reasons.add(childId + ": skipped as " + region);
        }
        children.sort(Comparator.comparingInt(SerpContainer::siblingIndex));
        List<SerpContainer> resultCluster = null;
        int largest = 0;
        for (List<SerpContainer> cluster : cluster(children)) {
            largest = Math.max(largest, cluster.size());
            if (cluster.size() >= settings.minimumRepeatedSiblingCount()
                    && (resultCluster == null || cluster.size() > resultCluster.size())) {
                resultCluster = cluster;
            }
        }
        if (resultCluster == null) {
            reasons.add("no repeated block structure: largest cluster of " + largest
                    + " similar siblings is below " + settings.minimumRepeatedSiblingCount());
            return new Detection(List.of(), reasons);
        }
        return new Detection(buildBlocks(document, resultCluster, reasons), reasons);
    }

    /** Blocks named by an engine adapter or a validated layout decision, taken at their word. */
    Detection detectNamed(SerpDocument document, List<String> blockIds) {
        List<String> reasons = new ArrayList<>();
        Map<String, SerpContainer> byId = document.containersById();
        List<SerpContainer> named = new ArrayList<>();
        for (String blockId : blockIds) {
            SerpContainer block = byId.get(blockId);
            if (block == null) reasons.add(blockId + ": no such container in this snapshot");
            else if (!block.visible()) reasons.add(blockId + ": not visible");
            else named.add(block);
        }
        if (named.isEmpty()) {
            reasons.add("no named result block survived the structural checks");
            return new Detection(List.of(), reasons);
        }
        return new Detection(buildBlocks(document, named, reasons), reasons);
    }

    private List<Block> buildBlocks(SerpDocument document, List<SerpContainer> blockContainers, List<String> reasons) {
        Map<String, List<SerpLink>> linksByContainer = new HashMap<>();
        for (SerpLink link : document.links()) {
            linksByContainer.computeIfAbsent(link.containerId(), ignored -> new ArrayList<>()).add(link);
        }
        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (SerpContainer container : document.containers()) childrenByParent.put(container.id(), container.childIds());
        List<Block> blocks = new ArrayList<>();
        for (SerpContainer block : blockContainers) {
            if (blocks.size() >= settings.maximumExtractedCandidates()) {
                reasons.add("candidate limit " + settings.maximumExtractedCandidates() + " reached");
                break;
            }
            List<SerpLink> blockLinks = subtreeLinks(block.id(), childrenByParent, linksByContainer);
            SerpLink primary = primaryLink(blockLinks);
            if (primary == null) {
                reasons.add(block.id() + ": no qualifying primary link");
                continue;
            }
            blocks.add(new Block(block.id(), blocks.size() + 1, primary, primary.text().trim(),
                    snippet(block, primary), primary.displayedDomain(), siteLinks(blockLinks, primary)));
        }
        return blocks;
    }

    /** Positive: external target, heading, early position, explanatory text. Negative: engine-internal targets. */
    SerpLink primaryLink(List<SerpLink> blockLinks) {
        SerpLink best = null;
        double bestScore = 0;
        for (int position = 0; position < blockLinks.size(); position++) {
            SerpLink link = blockLinks.get(position);
            if (link.redirect() == SearchRedirects.Status.UNRESOLVED || link.targetUrl().isEmpty() || !link.visible()) {
                continue;
            }
            if (link.text().trim().length() < settings.minimumTitleCharacters()) continue;
            double score = link.relation() == DomainNames.DomainRelation.EXTERNAL
                    ? settings.externalLinkWeight() : -penaltyFor(link.relation());
            if (link.insideHeading()) score += settings.headingLinkWeight();
            score += settings.titleLinkWeight();
            if (position == 0) score += settings.titleLinkWeight() / 2;
            if (!link.surroundingText().isBlank()) score += settings.snippetPresenceWeight() / 2;
            if (score > bestScore) {
                bestScore = score;
                best = link;
            }
        }
        if (best == null) return null;
        double maximum = settings.headingLinkWeight() + settings.titleLinkWeight() * 1.5
                + settings.snippetPresenceWeight() / 2;
        double confidence = maximum <= 0 ? 0 : Math.max(0, Math.min(1, bestScore / maximum));
        return confidence >= settings.minimumPrimaryLinkConfidence() ? best : null;
    }

    private double penaltyFor(DomainNames.DomainRelation relation) {
        return switch (relation) {
            case SAME_HOST -> settings.sameHostPenalty();
            case SUBDOMAIN -> settings.subdomainPenalty();
            case SAME_REGISTRABLE_DOMAIN -> settings.sameRegistrableDomainPenalty();
            default -> settings.unknownDomainPenalty();
        };
    }

    /**
     * Non-link text of the same block without title and displayed URL. The text next to the link is
     * preferred; the whole block is used when that is shorter (Google puts the snippet in a sibling).
     */
    String snippet(SerpContainer block, SerpLink primary) {
        String near = strip(primary.surroundingText(), primary);
        String whole = strip(block.textExcerpt(), primary);
        String snippet = whole.length() > near.length() ? whole : near;
        if (snippet.length() < settings.minimumSnippetCharacters()) return "";
        if (snippet.length() > settings.maximumSnippetCharacters()) {
            snippet = snippet.substring(0, settings.maximumSnippetCharacters()).trim();
        }
        return snippet;
    }

    private static String strip(String source, SerpLink primary) {
        String text = SerpDocumentParser.clean(source);
        for (String fragment : List.of(primary.text(), primary.displayedDomain(), primary.targetUrl(), primary.rawHref())) {
            String needle = SerpDocumentParser.clean(fragment);
            if (needle.isEmpty()) continue;
            int index;
            while ((index = text.indexOf(needle)) >= 0) {
                text = SerpDocumentParser.clean(text.substring(0, index) + " " + text.substring(index + needle.length()));
            }
        }
        return text;
    }

    private List<SerpLink> siteLinks(List<SerpLink> blockLinks, SerpLink primary) {
        List<SerpLink> siteLinks = new ArrayList<>();
        for (SerpLink link : blockLinks) {
            if (siteLinks.size() >= settings.maximumSiteLinksPerResult()) break;
            if (!link.id().equals(primary.id()) && link.visible() && !link.targetUrl().isEmpty()
                    && link.relation() == DomainNames.DomainRelation.EXTERNAL
                    && !link.targetUrl().equals(primary.targetUrl())) {
                siteLinks.add(link);
            }
        }
        return siteLinks;
    }

    /** Groups siblings whose structure signatures reach the Dice similarity threshold. */
    List<List<SerpContainer>> cluster(List<SerpContainer> siblings) {
        List<List<SerpContainer>> clusters = new ArrayList<>();
        List<String> representatives = new ArrayList<>();
        for (SerpContainer sibling : siblings) {
            int match = -1;
            for (int i = 0; i < representatives.size() && match < 0; i++) {
                if (similarity(representatives.get(i), sibling.structureSignature())
                        >= settings.resultBlockSimilarityThreshold()) {
                    match = i;
                }
            }
            if (match < 0) {
                clusters.add(new ArrayList<>());
                representatives.add(sibling.structureSignature());
                match = clusters.size() - 1;
            }
            clusters.get(match).add(sibling);
        }
        return clusters;
    }

    /** Dice coefficient over tag-token multisets (0..1). */
    static double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        Map<String, Integer> tokensA = tokens(a);
        Map<String, Integer> tokensB = tokens(b);
        int intersection = 0;
        for (Map.Entry<String, Integer> entry : tokensA.entrySet()) {
            intersection += Math.min(entry.getValue(), tokensB.getOrDefault(entry.getKey(), 0));
        }
        int total = tokensA.values().stream().mapToInt(Integer::intValue).sum()
                + tokensB.values().stream().mapToInt(Integer::intValue).sum();
        return total == 0 ? 0 : 2.0 * intersection / total;
    }

    private static Map<String, Integer> tokens(String signature) {
        Map<String, Integer> tokens = new HashMap<>();
        for (String token : signature.split("[^a-z0-9]+")) {
            if (!token.isEmpty()) tokens.merge(token, 1, Integer::sum);
        }
        return tokens;
    }

    private static List<SerpLink> subtreeLinks(String blockId, Map<String, List<String>> childrenByParent,
                                               Map<String, List<SerpLink>> linksByContainer) {
        List<SerpLink> links = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(blockId));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) continue;
            links.addAll(linksByContainer.getOrDefault(id, List.of()));
            queue.addAll(childrenByParent.getOrDefault(id, List.of()));
        }
        links.sort(Comparator.comparing(SerpLink::id)); // ids are assigned in document order
        return links;
    }
}
