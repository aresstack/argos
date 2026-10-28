package com.aresstack.argos.history;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec §3.1/§4 (Test A/B, classification part): normalized MIME types, optional categories first. */
class ResourceCategoryTest {
    @Test void theDefaultAllowlistIsContentWithParametersIgnored() {
        for (String type : new String[] {"text/html; charset=utf-8", "application/xhtml+xml", "application/json",
                "application/ld+json", "application/vnd.api+json", "application/xml", "text/xml",
                "application/atom+xml", "application/rss+xml", "text/plain;charset=ISO-8859-1", "text/markdown",
                "text/csv", "Application/JSON ; charset=\"utf-8\""}) {
            assertEquals(ResourceCategory.CONTENT, ResourceCategory.classify(type), type);
        }
    }

    @Test void knownOptionalCategoriesWinOverTextAndXml() {
        assertEquals(ResourceCategory.IMAGES, ResourceCategory.classify("image/svg+xml"));
        assertEquals(ResourceCategory.IMAGES, ResourceCategory.classify("image/png"));
        assertEquals(ResourceCategory.STYLES, ResourceCategory.classify("text/css; charset=utf-8"));
        for (String js : new String[] {"text/javascript", "application/javascript", "application/x-javascript",
                "text/ecmascript", "application/ecmascript", "text/x-javascript"}) {
            assertEquals(ResourceCategory.SCRIPTS, ResourceCategory.classify(js), js);
        }
        for (String font : new String[] {"font/woff2", "font/ttf", "application/font-woff", "application/x-font-ttf",
                "application/vnd.ms-fontobject", "application/x-font-opentype"}) {
            assertEquals(ResourceCategory.FONTS, ResourceCategory.classify(font), font);
        }
        assertEquals(ResourceCategory.AUDIO, ResourceCategory.classify("audio/mpeg"));
        assertEquals(ResourceCategory.VIDEO, ResourceCategory.classify("video/mp4"));
        assertEquals(ResourceCategory.PDF, ResourceCategory.classify("application/pdf"));
    }

    @Test void missingMalformedAndUnknownTypesAreOtherAndNeverText() {
        for (String type : new String[] {null, "", "garbage", "text", "/json", "application/octet-stream",
                "text/x-unknown", "application/wasm"}) {
            assertEquals(ResourceCategory.OTHER, ResourceCategory.classify(type), String.valueOf(type));
            assertEquals(ResourceCategory.TextKind.BINARY, ResourceCategory.textKind(type), String.valueOf(type));
        }
        assertEquals(ResourceCategory.TextKind.HTML, ResourceCategory.textKind("text/html"));
        assertEquals(ResourceCategory.TextKind.TEXT, ResourceCategory.textKind("application/ld+json"));
        assertEquals(ResourceCategory.TextKind.CODE, ResourceCategory.textKind("text/css"));
        assertEquals(ResourceCategory.TextKind.BINARY, ResourceCategory.textKind("image/svg+xml"));
    }

    @Test void environmentSwitchesAreStrictBooleansWithFalseDefaults() {
        SearchHistorySettings none = SearchHistorySettings.from(Map.of());
        assertTrue(none.enabled());
        assertTrue(none.captureDefaults().values().stream().noneMatch(Boolean::booleanValue));
        assertEquals(8, none.captureDefaults().size());
        SearchHistorySettings images = SearchHistorySettings.from(Map.of("ARGOS_SEARCH_HISTORY_CAPTURE_IMAGES", "TRUE",
                "ARGOS_SEARCH_HISTORY_CAPTURE_OTHER", "true"));
        assertTrue(images.captureDefaults().get(ResourceCategory.IMAGES));
        assertTrue(images.captureDefaults().get(ResourceCategory.OTHER));
        assertFalse(images.captureDefaults().get(ResourceCategory.FONTS), "OTHER never implies another category");
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> SearchHistorySettings.from(Map.of("ARGOS_SEARCH_HISTORY_CAPTURE_PDF", "yes")));
        assertTrue(invalid.getMessage().contains("ARGOS_SEARCH_HISTORY_CAPTURE_PDF"));
        assertThrows(IllegalArgumentException.class,
                () -> SearchHistorySettings.from(Map.of("ARGOS_SEARCH_HISTORY_CAPTURE_VIDEO", "")));
        // The former activation switch has no effect any more.
        assertDoesNotThrow(() -> SearchHistorySettings.from(Map.of("ARGOS_SEARCH_HISTORY_HTML", "true")));
    }
}
