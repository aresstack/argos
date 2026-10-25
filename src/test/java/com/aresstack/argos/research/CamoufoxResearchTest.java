package com.aresstack.argos.research;

import com.aresstack.argos.ArgosConfig;
import com.aresstack.argos.CamoufoxBrowser;
import com.aresstack.argos.web.SearchHit;
import com.aresstack.argos.web.SearchResults;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in (-PtestCamoufox=true): a research run that reads every page through real Camoufox. The search is
 * scripted (web_search through Camoufox has its own test); the pages come from one loopback server,
 * reached as "localhost" and "127.0.0.1" so that the run sees two sites.
 */
class CamoufoxResearchTest {
    @TempDir Path temporary;

    @Test @Timeout(value = 200, unit = TimeUnit.SECONDS)
    void researchesALocalWorldThroughRealCamoufox() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser smoke");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = site.getAddress().getPort();
        String other = "http://127.0.0.1:" + port;
        SearchResults hits = new SearchResults(List.of(
                new SearchHit("Perovskite solar cell efficiency record", other + "/perovskite", "Tandem cells."),
                new SearchHit("Perovskite solar cell efficiency noodles", other + "/noodles", "Off topic."),
                new SearchHit("Solar cell efficiency review", "http://localhost:" + port + "/review", "Thin film.")),
                List.of());
        site.createContext("/perovskite", exchange -> respond(exchange, 200, article("Perovskite solar cell efficiency",
                "<a href='/stability'>Perovskite solar cell efficiency and stability</a><a href='/imprint'>Imprint</a>")));
        site.createContext("/noodles", exchange -> respond(exchange, 200,
                "<html><head><title>Noodles</title></head><body><p>Boil water.</p></body></html>"));
        site.createContext("/stability", exchange -> respond(exchange, 200, article("Stability", "")));
        site.createContext("/review", exchange -> respond(exchange, 200, article("Review", "")));
        site.start();
        try (CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, local.executable(),
                local.launchOptions(), temporary.resolve("profile"), true)
                .withNetwork(com.aresstack.argos.TestNetworks.LOOPBACK))) {
            ResearchService research = new ResearchService(query -> hits, new CamoufoxResearchPageReader(browser),
                    new ResearchBudget(1, 8, 5, 3, Duration.ofSeconds(150), 3, 2, 3, 1));

            ResearchReport report = research.research("perovskite solar cell efficiency", null);

            assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, report.stopReason(), report.format());
            assertEquals(List.of(other + "/perovskite", other + "/stability", "http://localhost:" + port + "/review"),
                    report.sources().stream().map(ResearchSource::url).toList());
        } finally {
            site.stop(0);
        }
    }

    private static String article(String title, String links) {
        return "<html><head><title>" + title + "</title></head><body><main><h1>" + title + "</h1><p>"
                + "Perovskite solar cell efficiency keeps improving in the lab. ".repeat(8) + "</p>" + links
                + "</main></body></html>";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
