package com.aresstack.argos.history;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The ported collector and the Java-side archive cleanup, without a browser (see the browser test for §10 item 6). */
class HtmlCollectorTest {

    @Test void theCollectorIsCamToolsInlineJsonModeReturningItsResult() {
        String function = HtmlCollector.collectorFunction();
        // Unchanged parts of kCaptureJs.
        assertTrue(function.contains("doc.querySelectorAll('script,link[rel~=\"preload\"],link[rel~=\"modulepreload\"]')"));
        assertTrue(function.contains("if (b.size > 8*1024*1024) return null;"));
        assertTrue(function.contains("var re = /url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)/g"));
        assertTrue(function.contains("a.setAttribute(\"href\", new URL(a.getAttribute(\"href\"), location.href).href)"));
        // Inline JSON mode, result returned to evaluate() instead of the Qt callback.
        assertTrue(function.contains("var SPLIT = false;") && function.contains("var AS_JSON = true;"));
        assertFalse(function.contains("window[CB]"), "no page global is called or created");
        assertTrue(function.strip().endsWith("return result;\n}") || function.strip().endsWith("return result;\r\n}"));
        String evaluation = HtmlCollector.evaluation(2_000);
        assertTrue(evaluation.startsWith("async () => {") && evaluation.contains("2000"), evaluation);
    }

    @Test void parsesTheCollectorResult() throws Exception {
        Optional<HtmlCollector.Collected> collected = HtmlCollector.parse(
                "{\"title\":\"T\",\"url\":\"https://x.example/\",\"html\":\"<!DOCTYPE html><html></html>\",\"assets\":[]}");
        assertEquals("T", collected.orElseThrow().title());
        assertTrue(HtmlCollector.parse("").isEmpty(), "timeout or page error");
        assertTrue(HtmlCollector.parse(null).isEmpty());
        assertTrue(HtmlCollector.parse("{\"html\":\"\"}").isEmpty());
    }

    @Test void theArchiveKeepsNoFormValuesHandlersOrSecrets() {
        String archived = ArchiveSanitizer.sanitize("""
                <!DOCTYPE html><html><head><meta http-equiv="refresh" content="0;url=/x"></head>
                <body onload="track()"><form><input type="password" name="pw" value="hunter2">
                <input type="hidden" name="csrf_token" value="abc"><input name="q" value="private search">
                <input type="submit" value="Go"><textarea>draft text</textarea></form>
                <a href="javascript:steal()">x</a><a href="https://x.example/cb?session=s3cr3t&page=2">next</a>
                <img src="data:image/png;base64,AAAA" onerror="x()"><script>alert(1)</script></body></html>
                """, "https://x.example/");
        for (String gone : new String[] {"hunter2", "abc", "private search", "draft text", "onload", "onerror",
                "javascript:", "refresh", "<script", "s3cr3t"}) {
            assertFalse(archived.contains(gone), gone + " in " + archived);
        }
        assertTrue(archived.contains("value=\"Go\""));
        assertTrue(archived.contains("data:image/png;base64,AAAA"), "inlined resources stay");
        assertTrue(archived.contains("page=2"));
    }
}
