package com.aresstack.argos;

import com.aresstack.argos.page.DocumentType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Text of a feed or other source document that Firefox saved as a download instead of rendering it. */
class SavedDocumentDecodeTest {
    @Test
    void theHeaderCharsetWins() {
        byte[] latin = "<rss>Grüße</rss>".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("<rss>Grüße</rss>",
                CamoufoxBrowser.decode(latin, "application/rss+xml; charset=ISO-8859-1", DocumentType.FEED));
    }

    @Test
    void xmlWithoutHeaderCharsetUsesItsDeclaration() {
        byte[] latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss>Grüße</rss>"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss>Grüße</rss>",
                CamoufoxBrowser.decode(latin, "application/rss+xml", DocumentType.FEED));
    }

    @Test
    void aByteOrderMarkIsHonouredAndDropped() {
        byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        byte[] body = "<rss>ß</rss>".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        assertEquals("<rss>ß</rss>", CamoufoxBrowser.decode(all, "application/rss+xml", DocumentType.FEED));
    }

    @Test
    void otherwiseUtf8() {
        assertEquals("{\"a\":\"ü\"}", CamoufoxBrowser.decode("{\"a\":\"ü\"}".getBytes(StandardCharsets.UTF_8),
                "application/json", DocumentType.JSON));
    }
}
