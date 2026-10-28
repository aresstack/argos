package com.aresstack.argos.camoufox;

import com.aresstack.argos.ArgosConfig;
import com.aresstack.argos.CamoufoxBrowser;
import com.aresstack.argos.network.NetworkSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real browser smoke for the self-installed Camoufox: no browser path, no launch.json. Downloads the
 * published Argos distribution (about 1.3 GB) into {@code BROWSER_DIR}, so it only runs with
 * {@code -PtestProvisionedCamoufox}.
 */
class CamoufoxProvisionedSmokeTest {
    @TempDir Path temporary;

    @Test @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void startsTheInstalledCamoufoxWithTheBuiltInProfile() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testProvisionedCamoufox"), "Opt-in: downloads Camoufox");
        ArgosConfig.BrowserInstallation installation = ArgosConfig.BrowserInstallation.fromEnvironment();
        CamoufoxInstaller installer = CamoufoxInstaller.shared(installation.root(), installation.mirror());
        Path executable = installer.installBlocking();
        assertTrue(Files.isRegularFile(executable), "installed executable");
        assertEquals(executable, installer.awaitExecutable(Duration.ofSeconds(1)), "second call reuses the install");

        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            String body = path.startsWith("/second")
                    ? "<html><head><title>Second tab</title></head><body><p>Other page</p></body></html>"
                    : "<html><head><title>Argos provisioned smoke</title></head><body><h1>Built-in profile</h1>"
                    + "<p id='cookie'>" + (cookie == null ? "no-cookie" : cookie) + "</p></body></html>";
            if (path.startsWith("/set")) exchange.getResponseHeaders().add("Set-Cookie", "argos=isolated; Path=/");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        site.start();
        String base = "http://localhost:" + site.getAddress().getPort();
        NetworkSettings loopback = NetworkSettings.parse("strict", "127.0.0.1,::1", null, null);
        JsonNode profile = new ObjectMapper().readTree(
                CamoufoxProvisionedSmokeTest.class.getResourceAsStream("/camoufox/default-launch.json")).path("config");
        try (CamoufoxBrowser first = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null,
                temporary.resolve("profile-a"), true).withNetwork(loopback));
             CamoufoxBrowser second = new CamoufoxBrowser(new ArgosConfig("127.0.0.1", 0, null, null,
                     temporary.resolve("profile-b"), true).withNetwork(loopback))) {
            Map<String, Object> navigated = first.navigate(base + "/set");
            assertEquals("Argos provisioned smoke", navigated.get("title"));
            assertTrue(first.snapshot(4000).text().contains("Built-in profile"));

            Map<String, Object> tab = first.newTab(base + "/second");
            String tabId = String.valueOf(tab.get("tab"));
            first.selectTab(tabId);
            assertEquals("Second tab", first.evaluate("() => document.title"));
            assertEquals("visible", first.evaluate("() => document.visibilityState"), "activated tab is visible");

            // The fingerprint of the built-in profile reaches the page.
            assertEquals(profile.path("navigator.userAgent").asText(), first.evaluate("() => navigator.userAgent"));
            assertEquals(profile.path("navigator.platform").asText(), first.evaluate("() => navigator.platform"));
            assertEquals(profile.path("navigator.hardwareConcurrency").asInt(),
                    ((Number) first.evaluate("() => navigator.hardwareConcurrency")).intValue());
            assertEquals(profile.path("screen.width").asInt(),
                    ((Number) first.evaluate("() => screen.width")).intValue());
            assertEquals(profile.path("screen.height").asInt(),
                    ((Number) first.evaluate("() => screen.height")).intValue());

            // Media devices: enumeration works and, before any grant, exposes no audio output (Firefox rule).
            Object outputs = first.evaluate("async () => (await navigator.mediaDevices.enumerateDevices())"
                    + ".filter(d => d.kind === 'audiooutput').length");
            assertEquals(0, ((Number) outputs).intValue());

            // The same profile keeps its cookie; another profile directory does not see it.
            first.navigate(base + "/read");
            assertTrue(String.valueOf(first.evaluate("() => document.querySelector('#cookie').textContent"))
                    .contains("argos=isolated"));
            second.navigate(base + "/read");
            assertEquals("no-cookie", second.evaluate("() => document.querySelector('#cookie').textContent"));

            // The network boundary still holds: a loopback address the policy does not admit stays unreachable.
            HttpServer blocked = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0), 0);
            blocked.createContext("/", exchange -> {
                byte[] bytes = "<html><body>reached-forbidden-target</body></html>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            blocked.start();
            try {
                String forbidden = "http://127.0.0.2:" + blocked.getAddress().getPort() + "/";
                try {
                    first.navigate(forbidden);
                    assertFalse(first.snapshot(4000).text().contains("reached-forbidden-target"));
                } catch (RuntimeException refused) {
                    // refused before or by the proxy: expected
                }
            } finally {
                blocked.stop(0);
            }
        } finally {
            site.stop(0);
        }
    }
}
