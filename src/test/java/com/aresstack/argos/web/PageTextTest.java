package com.aresstack.argos.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PageTextTest {
    @Test void keepsBlockStructureAndDropsInvisibleContent() {
        PageCapture page = PageText.capture("https://s.example/", """
                <html><head><title>Paper</title><style>p{}</style></head><body>
                <h1>Results</h1><script>var x = 1;</script>
                <p>First   line</p><p>Second <b>bold</b> line</p>
                <div hidden>secret</div><div style="display: none">gone</div>
                <span aria-hidden="true">icon</span>
                <ul><li>one</li><li>two</li></ul>
                <table><tr><td>a</td><td>b</td></tr></table>
                </body></html>""");
        assertEquals("Paper", page.title());
        assertEquals("Results\n\nFirst line\n\nSecond bold line\n\none\n\ntwo\n\na b", page.text());
    }

    @Test void readsDocumentsWithoutHtmlStructure() {
        assertEquals("{\"a\": 1}", PageText.capture("https://s.example/x.json", "{\"a\": 1}").text());
        assertEquals("", PageText.capture("https://s.example/", null).text());
    }
}
