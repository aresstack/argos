package com.aresstack.argos.network;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.aresstack.argos.network.SecretMasker.MASK;
import static org.junit.jupiter.api.Assertions.*;

class SecretMaskerTest {
    private final SecretMasker masker = new SecretMasker();

    @Test void masksCredentialHeadersButKeepsOrdinaryOnes() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer abc.def.ghi");
        headers.put("X-Api-Key", "k-123456");
        headers.put("X-CSRF-Token", "csrf-value");
        headers.put("Accept", "application/json");
        Map<String, String> masked = masker.headers(headers);
        assertEquals(MASK, masked.get("Authorization"));
        assertEquals(MASK, masked.get("X-Api-Key"));
        assertEquals(MASK, masked.get("X-CSRF-Token"));
        assertEquals("application/json", masked.get("Accept"));
    }

    @Test void keepsCookieNamesAndAttributesOnly() {
        assertEquals("sid=" + MASK + "; theme=" + MASK, masker.header("Cookie", "sid=s3cr3t; theme=dark"));
        String setCookie = masker.header("Set-Cookie",
                "session=abc123; Path=/; HttpOnly; Secure\ntracking=xyz; Max-Age=60");
        assertEquals("session=" + MASK + "; Path=/; HttpOnly; Secure\ntracking=" + MASK + "; Max-Age=60", setCookie);
        assertFalse(setCookie.contains("abc123"));
    }

    @Test void masksSecretQueryParametersAndDropsUserInfoAndFragment() {
        String url = masker.url("https://user:pw@example.org/search?q=camoufox&access_token=t0k3n&page=2#frag");
        assertEquals("https://example.org/search?q=camoufox&access_token=" + MASK + "&page=2", url);
        assertEquals("https://example.org/search",
                masker.urlWithoutQuery("https://user:pw@example.org/search?q=1"));
        assertEquals("data:", masker.url("data:text/plain,hello"));
    }

    @Test void masksJsonFormAndFreeTextBodies() {
        String json = masker.body("{\"user\":\"ada\",\"password\":\"hunter2\",\"nested\":{\"refresh_token\":\"r\"},"
                + "\"items\":[{\"apiKey\":\"k\",\"title\":\"ok\"}]}", "application/json");
        assertFalse(json.contains("hunter2"));
        assertFalse(json.contains("\"r\""));
        assertTrue(json.contains("\"title\":\"ok\""));
        assertTrue(json.contains("\"user\":\"ada\""));

        assertEquals("user=ada&password=" + MASK + "&client_secret=" + MASK,
                masker.body("user=ada&password=hunter2&client_secret=xyz", "application/x-www-form-urlencoded"));

        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl";
        String html = "<script>window.cfg={\"csrfToken\":\"abc\",\"lang\":\"de\"}; var t='" + jwt + "';</script>"
                + "<input type=\"hidden\" name=\"authenticity_token\" value=\"zzz\">Authorization: Bearer abcdefghijkl";
        String masked = masker.body(html, "text/html");
        assertFalse(masked.contains("abc\""), masked);
        assertFalse(masked.contains(jwt));
        assertFalse(masked.contains("zzz"));
        assertFalse(masked.contains("abcdefghijkl"));
        assertTrue(masked.contains("\"lang\":\"de\""));
    }

    @Test void leavesHarmlessTextUntouched() {
        String text = "Research results for \"monkey\": 42 papers";
        assertEquals(text, masker.text(text));
        assertEquals("{\"title\":\"Paper\",\"year\":2024}", masker.body("{\"title\":\"Paper\",\"year\":2024}", "application/json"));
    }
}
