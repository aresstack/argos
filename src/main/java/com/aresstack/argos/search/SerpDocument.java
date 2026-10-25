package com.aresstack.argos.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A parsed search page: container tree, links and the containers an engine adapter recognised. */
record SerpDocument(String snapshotId, String url, String title, String visibleText,
                    List<SerpContainer> containers, List<SerpLink> links,
                    List<String> adapterBlockIds, boolean truncated) {

    SerpDocument {
        containers = List.copyOf(containers);
        links = List.copyOf(links);
        adapterBlockIds = List.copyOf(adapterBlockIds);
    }

    Map<String, SerpContainer> containersById() {
        Map<String, SerpContainer> byId = new LinkedHashMap<>();
        for (SerpContainer container : containers) byId.put(container.id(), container);
        return byId;
    }
}
