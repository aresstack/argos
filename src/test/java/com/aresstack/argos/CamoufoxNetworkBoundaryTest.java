package com.aresstack.argos;

import com.aresstack.argos.network.NetworkSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in acceptance for the network boundary (analysis 5.4, step 0c) against the real Camoufox build:
 * redirect to loopback, subresource to the LAN and WebRTC UDP are refused; the admitted site works.
 * DNS rebinding needs a controllable resolver and is covered by Socks5ProxyTest.
 */
class CamoufoxNetworkBoundaryTest {
    @TempDir Path temporary;

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void refusesInternalTargetsThatThePageOrARedirectRequests() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();

        // Canary on a second loopback address: only 127.0.0.1 is admitted below.
        AtomicInteger canaryHits = new AtomicInteger();
        ServerSocket canary = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.2"));
        Thread.ofVirtual().start(() -> {
            while (!canary.isClosed()) {
                try (Socket ignored = canary.accept()) {
                    canaryHits.incrementAndGet();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        String internal = "127.0.0.2:" + canary.getLocalPort();
        byte[] page = ("<html><head><title>Boundary</title></head><body><p>public part</p>"
                + "<img src='http://192.168.255.254:81/argos.png'></body></html>").getBytes(StandardCharsets.UTF_8);
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        site.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", "http://" + internal + "/");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (var out = exchange.getResponseBody()) { out.write(page); }
        });
        site.start();
        String origin = "http://127.0.0.1:" + site.getAddress().getPort();
        try (DatagramSocket stun = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                     "127.0.0.1", 0, local.executable(), local.launchOptions(),
                     temporary.resolve("profile"), true)
                     .withNetwork(NetworkSettings.parse("strict", "127.0.0.1", null, null)))) {
            assertEquals("Boundary", browser.navigate(origin + "/").get("title"));
            assertEquals(true, ((Map<?, ?>) browser.status().get("network")).get("proxyRunning"));

            assertThrows(IllegalStateException.class, () -> browser.navigate(origin + "/redirect"));
            assertEquals(0, canaryHits.get(), "redirect target must not be contacted");

            // Test-only main-world script: ICE gathering against a loopback STUN server.
            browser.navigate(origin + "/");
            browser.evaluate("() => { const pc = new RTCPeerConnection({iceServers:[{urls:'stun:127.0.0.1:"
                    + stun.getLocalPort() + "'}]}); pc.createDataChannel('x');"
                    + " pc.createOffer().then(o => pc.setLocalDescription(o)); setTimeout(() => pc.close(), 4000); }");
            stun.setSoTimeout(5_000);
            assertThrows(SocketTimeoutException.class, () -> stun.receive(new DatagramPacket(new byte[512], 512)),
                    "WebRTC must not send UDP around the proxy");

            List<Object> blocked = browser.networkEvents().stream()
                    .filter(event -> "blocked".equals(event.get("kind"))).map(event -> event.get("url")).toList();
            assertTrue(blocked.contains("192.168.255.254:81"), blocked.toString());
            assertTrue(blocked.contains(internal), blocked.toString());
        } finally {
            site.stop(0);
            canary.close();
        }
    }
}
