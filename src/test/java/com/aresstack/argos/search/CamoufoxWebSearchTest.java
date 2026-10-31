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

/**
 * Opt-in (-PtestCamoufox=true): a local search world behind a consent wall, searched through real Camoufox. The
 * engine's start page records, in hidden form fields the browser itself submits, whether the typed keys and the
 * Enter key reached the page as trusted events.
 */
class CamoufoxWebSearchTest {
    /** A start page like the engines' own: one form, one field; the script only observes. */
    private static final String HOME = """
            <html><head><title>Local search</title></head><body>
            <form action="/search"><input type="search" name="q" autocomplete="off">
            <input type="hidden" name="keys" value=""><input type="hidden" name="enter" value=""></form>
            <script>
              const form = document.querySelector('form');
              form.q.addEventListener('keydown', e => {
                if (e.key === 'Enter') form.enter.value = String(e.isTrusted);
                else form.keys.value += e.isTrusted ? 't' : 'u';
              });
            </script></body></html>""";

    @TempDir Path temporary;

    @Test @Timeout(value = 160, unit = TimeUnit.SECONDS)
    void searchesALocalEngineThroughItsSearchFieldAndPassesItsConsentWallWithTrustedInput() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        AtomicBoolean consented = new AtomicBoolean();
        List<String> searches = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> respond(exchange, exchange.getRequestURI().getPath().equals("/") ? 200 : 404,
                exchange.getRequestURI().getPath().equals("/") ? HOME : "<html><body>404</body></html>"));
        site.createContext("/search", exchange -> {
            if (exchange.getRequestURI().getRawQuery() != null) searches.add(exchange.getRequestURI().getRawQuery());
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
        String home = "http://localhost:" + site.getAddress().getPort() + "/";
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, local.executable(),
                local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(com.aresstack.argos.TestNetworks.LOOPBACK))) {
            WebSearchService service = new WebSearchService(new CamoufoxSearchPageBrowser(browser),
                    new SearchSettings(List.of(SearchEngine.custom(home)), 10, Duration.ofMinutes(10)),
                    SearchLayoutResolver.NONE, Clock.systemUTC());

            WebSearchService.Report report = service.search("solar");

            assertTrue(searches.get(0).matches("q=solar&keys=t{5,}&enter=true"),
                    "typed and submitted by trusted key events through the page's own form: " + searches);
            assertTrue(consented.get(), "consent button must be clicked with real input");
            assertEquals(4, report.hits().size());
            assertEquals("https://example.org/perovskite", report.hits().get(0).url());
        } finally {
            site.stop(0);
        }
    }

    @Test @Timeout(value = 160, unit = TimeUnit.SECONDS)
    void convenienceSearchTypesIntoEachEnginesOwnTab() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        List<String> searches = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> respond(exchange, exchange.getRequestURI().getPath().equals("/") ? 200 : 404,
                exchange.getRequestURI().getPath().equals("/") ? HOME : "<html><body>404</body></html>"));
        site.createContext("/search", exchange -> {
            searches.add(exchange.getRequestHeaders().getFirst("Host") + " " + exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, SerpFixtures.html("generic-cards.html"));
        });
        site.start();
        int port = site.getAddress().getPort();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, local.executable(),
                local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(com.aresstack.argos.TestNetworks.LOOPBACK))) {
            // Two engines on two hosts, so both start pages load at the same time under the gate.
            ParallelWebSearch search = new ParallelWebSearch(browser,
                    new com.aresstack.argos.convenience.DomainCoordinator(4, System::currentTimeMillis),
                    List.of(SearchEngine.custom("http://localhost:" + port + "/"),
                            SearchEngine.custom("http://127.0.0.1:" + port + "/")), 10, false, 20_000);

            List<ParallelWebSearch.EngineRun> runs = search.search("solar");

            assertEquals(List.of(ParallelWebSearch.EngineStatus.OK, ParallelWebSearch.EngineStatus.OK),
                    runs.stream().map(ParallelWebSearch.EngineRun::status).toList(), runs.toString());
            assertNotEquals(runs.get(0).tab(), runs.get(1).tab());
            assertEquals(2, searches.size(), searches.toString());
            searches.forEach(line -> assertTrue(line.matches("\\S+ q=solar&keys=t{5,}&enter=true"), line));
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
