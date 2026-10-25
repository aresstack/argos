package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AriaTreeTest {
    /** Shape of a real Firefox 158 / Playwright 1.62 AI-mode snapshot. */
    static final String SNAPSHOT = """
            - generic [active] [ref=e1]:
              - navigation [ref=e2]:
                - link "Alpha" [ref=e3] [cursor=pointer]:
                  - /url: /a
                - link "Beta link" [ref=e4] [cursor=pointer]:
                  - /url: https://ex.org/b?q=1
              - main [ref=e5]:
                - heading "Main title" [level=1] [ref=e6]
                - paragraph [ref=e8]: Para text bold.
                - generic [ref=e9]:
                  - text: Suchbegriff
                  - textbox "Suchbegriff" [ref=e10]: pre
                  - searchbox "Suchen..." [ref=e11]
                  - button "Los" [ref=e12]
                  - checkbox "Merken" [checked] [ref=e13]
                  - combobox "Sprache" [ref=e14]:
                    - option "DE" [selected]
                    - option "EN"
                  - textbox "Passwort" [ref=e16]: secret
                - table [ref=e17]:
                  - rowgroup [ref=e18]:
                    - row [ref=e19]:
                      - columnheader "Name" [ref=e20]
                      - columnheader "Wert" [ref=e21]
                    - row [ref=e22]:
                      - cell "a" [ref=e23]
                      - cell "1" [ref=e24]
                - list [ref=e25]:
                  - listitem [ref=e26]: eins
                  - listitem [ref=e27]: zwei
                - button "Fake button" [ref=e28]
                - button "Disabled" [disabled] [ref=e29]
            """;

    @Test void parsesRolesNamesAttributesAndProperties() {
        AriaTree tree = AriaTree.parse(SNAPSHOT);
        assertEquals(1, tree.roots().size());
        List<AriaTree.Node> links = tree.findAll(n -> n.role().equals("link"));
        assertEquals(List.of("Alpha", "Beta link"), links.stream().map(AriaTree.Node::name).toList());
        assertEquals("/a", links.get(0).property("url"));
        assertEquals("https://ex.org/b?q=1", links.get(1).property("url"));
        AriaTree.Node heading = tree.findAll(n -> n.role().equals("heading")).get(0);
        assertEquals("1", heading.attribute("level"));
        assertEquals("e6", heading.ref());
        AriaTree.Node textbox = tree.findAll(n -> n.ref().equals("e10")).get(0);
        assertEquals("pre", textbox.text());
        assertTrue(tree.findAll(n -> n.ref().equals("e13")).get(0).has("checked"));
        assertEquals("Para text bold.", tree.findAll(n -> n.role().equals("paragraph")).get(0).label());
    }

    @Test void parsesQuotedKeysAndEscapedNames() {
        AriaTree tree = AriaTree.parse("""
                - 'heading "Hello: World" [level=2] [ref=e3]'
                - button "Say \\"hi\\"" [ref=e4]
                - paragraph: "Quoted: text"
                - 'link "It''s here" [ref=e5]':
                  - /url: "/x?y=1"
                """);
        assertEquals("Hello: World", tree.roots().get(0).name());
        assertEquals("2", tree.roots().get(0).attribute("level"));
        assertEquals("Say \"hi\"", tree.roots().get(1).name());
        assertEquals("Quoted: text", tree.roots().get(2).text());
        assertEquals("It's here", tree.roots().get(3).name());
        assertEquals("/x?y=1", tree.roots().get(3).property("url"));
    }

    @Test void emptySnapshotHasNoNodes() {
        assertTrue(AriaTree.parse("").roots().isEmpty());
        assertTrue(AriaTree.parse(null).roots().isEmpty());
    }
}
