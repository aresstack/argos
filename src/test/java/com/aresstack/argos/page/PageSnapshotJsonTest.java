package com.aresstack.argos.page;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The MCP layer serializes these types with a plain ObjectMapper. */
class PageSnapshotJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void keepsLegacySnapshotKeysAndUsesLowerCaseDocumentTypes() throws Exception {
        JsonNode node = json.valueToTree(new PageSnapshot("https://a.org/feed", "", 200, DocumentType.FEED,
                "application/rss+xml", "Feed: A", false, 7, "response", null));
        assertEquals("https://a.org/feed", node.get("url").asText());
        assertEquals("", node.get("title").asText());
        assertEquals("Feed: A", node.get("text").asText());
        assertEquals("feed", node.get("documentType").asText());
        assertFalse(node.has("hint"));
    }

    @Test
    void serializesReadinessReport() throws Exception {
        JsonNode node = json.valueToTree(new ReadinessReport("timeout", 8000, List.of("dom-stable"),
                List.of("network-quiet")));
        assertEquals("timeout", node.get("state").asText());
        assertEquals("network-quiet", node.get("pending").get(0).asText());
        assertFalse(node.has("ready"));
    }
}
