package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PageControlsTest {
    private final PageControls controls = PageControls.from("http://local/", AriaTree.parse(AriaTreeTest.SNAPSHOT));

    @Test void listsOperableControlsInDocumentOrderWithoutDisabledOnes() {
        List<String> lines = controls.all().stream().map(PageControls.Control::line).toList();
        assertEquals(List.of(
                "[1] textbox \"Suchbegriff\" = pre",
                "[2] searchbox \"Suchen...\"",
                "[3] button \"Los\"",
                "[4] checkbox \"Merken\" (checked)",
                "[5] combobox \"Sprache\" = DE",
                "[6] textbox \"Passwort\" = secret",
                "[7] button \"Fake button\""), lines);
        assertEquals("role=button name=\"Los\"", controls.all().get(2).locator());
    }

    @Test void hidesPasswordValues() {
        PageControls masked = controls.withHiddenValues(Set.of("e16"));
        assertEquals("[6] textbox \"Passwort\" = (hidden)", masked.all().get(5).line());
    }

    @Test void filtersByRoleNameOrValue() {
        assertEquals(List.of("Suchbegriff", "Suchen..."),
                controls.filtered("such", 10).stream().map(PageControls.Control::name).toList());
        assertEquals(2, controls.filtered("BUTTON", 10).size());
    }

    @Test void resolvesNumbersRefsAndSemanticLocators() {
        assertEquals("e12", controls.resolve("3").ref());
        assertEquals("e12", controls.resolve("[3]").ref());
        assertEquals("e6", controls.resolve("e6").ref());
        assertEquals("e6", controls.resolve("ref=e6").ref());
        assertEquals("e12", controls.resolve("role=button name=\"Los\"").ref());
        assertEquals("e10", controls.resolve("name=\"Suchbegriff\"").ref());
        assertEquals("e12", controls.resolve("los").ref());
        assertEquals("e12", controls.resolve("\"Los\"").ref());
        assertEquals("e3", controls.resolve("Alpha").ref(), "links are valid targets");
        assertEquals("e6", controls.resolve("role=heading name=\"Main title\"").ref());
    }

    @Test void exactNameWinsOverPartialAndUniquePartialIsAccepted() {
        assertEquals("e10", controls.resolve("Suchbegriff").ref());
        assertEquals("e28", controls.resolve("fake").ref());
    }

    @Test void ambiguousTargetsNameTheCandidates() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> controls.resolve("such"));
        assertTrue(error.getMessage().contains("2 elements match"), error.getMessage());
        assertTrue(error.getMessage().contains("Suchbegriff"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> controls.resolve("role=button"));
    }

    @Test void unknownNumbersAndRefsFail() {
        assertThrows(IllegalArgumentException.class, () -> controls.resolve("99"));
        assertThrows(IllegalArgumentException.class, () -> controls.resolve("e999"));
        assertThrows(IllegalArgumentException.class, () -> controls.resolve(" "));
        assertThrows(IllegalArgumentException.class, () -> controls.resolve("role=button name=\"Nope\""));
    }

    @Test void disabledControlsAreNotTargets() {
        PageControls.Target target = controls.resolve("Disabled");
        assertFalse(target.hasRef(), "falls back to Playwright's text engine, which then reports what it finds");
    }

    @Test void textWithoutControlFallsBackToPlaywrightTextEngine() {
        PageControls.Target target = controls.resolve("Para text");
        assertFalse(target.hasRef());
        assertEquals("text", target.fallbackKind());
        assertEquals("Para text", target.fallbackValue());
        assertEquals("css", controls.resolve("css=#main > button").fallbackKind());
        assertEquals("placeholder", controls.resolve("placeholder=\"Mail\"").fallbackKind());
    }

    @Test void explicitSelectorKindsAreNotMatchedAgainstOtherControlNames() {
        // A button named "Los" must not win over the placeholder, label or text engine.
        for (String kind : List.of("placeholder", "label", "text")) {
            PageControls.Target target = controls.resolve(kind + "=\"Los\"");
            assertFalse(target.hasRef(), kind);
            assertEquals(kind, target.fallbackKind());
            assertEquals("Los", target.fallbackValue());
        }
    }
}
