package com.aresstack.argos.search;

import com.aresstack.argos.ArgosConfig;
import com.aresstack.argos.CamoufoxBrowser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in (-PtestCamoufox=true): a local search world behind a consent wall, searched through real Camoufox. */
class CamoufoxWebSearchTest {
    @TempDir Path temporary;

    @Test @Timeout(value = 160, unit = TimeUnit.SECONDS)
    void searchesALocalEngineAndPassesItsConsentWallWithTrustedInput() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        AtomicBoolean consented = new AtomicBoolean();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/search", exchange -> {
            String page = consented.get() ? SerpFixtures.html("generic-cards.html")
                    : "<html><head><title>Consent</title></head><body><div class='cookie-banner'><p>We use cookies.</p>"
                    + "<form method='post' action='/accept'><button type='submit'>Reject all</button></form>"
                    + "</div></body></html>";
            respond(exchange, 200, page);
        });
        site.createContext("/accept", exchange -> {
            consented.set(true);
            exchange.getResponseHeaders().add("Location", "/search?q=solar");
            respond(exchange, 303, "");
        });
        site.start();
        String template = "http://localhost:" + site.getAddress().getPort() + "/search?q={query}";
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, local.executable(),
                local.launchOptions(), temporary.resolve("profile"), true))) {
            WebSearchService service = new WebSearchService(new CamoufoxSearchPageBrowser(browser),
                    new SearchSettings(List.of(SearchEngine.custom(template)), 10, Duration.ofMinutes(10)),
                    SearchLayoutResolver.NONE, Clock.systemUTC());

            WebSearchService.Report report = service.search("solar");

            assertTrue(consented.get(), "consent button must be clicked with real input");
            assertEquals(4, report.hits().size());
            assertEquals("https://example.org/perovskite", report.hits().get(0).url());
        } finally {
            site.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
