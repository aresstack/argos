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

    private static InetAddress nonLoopbackIpv4() throws IOException {
        for (var network : java.net.NetworkInterface.networkInterfaces().toList()) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : network.inetAddresses().toList()) {
                if (address instanceof java.net.Inet4Address) return address;
            }
        }
        System.err.println("No non-loopback IPv4 interface: WebRTC part of the boundary test skipped.");
        return null;
    }

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
        // Firefox never uses loopback for ICE, so a loopback STUN server cannot see a leak.
        InetAddress lan = nonLoopbackIpv4();
        try (DatagramSocket stun = lan == null ? null : new DatagramSocket(0, lan);
             CamoufoxBrowser browser = new CamoufoxBrowser(new ArgosConfig(
                     "127.0.0.1", 0, local.executable(), local.launchOptions(),
                     temporary.resolve("profile"), true)
                     .withNetwork(NetworkSettings.parse("strict", "127.0.0.1", null, null)))) {
            assertEquals("Boundary", browser.navigate(origin + "/").get("title"));
            assertEquals(true, ((Map<?, ?>) browser.status().get("network")).get("proxyRunning"));

            assertThrows(IllegalStateException.class, () -> browser.navigate(origin + "/redirect"));
            assertEquals(0, canaryHits.get(), "redirect target must not be contacted");

            if (stun != null) {
                // Test-only main-world script: ICE gathering against a STUN port on this machine's LAN
                // address, in the proxied document and in an about:blank frame, which has no HTTP channel
                // and so is not "behind the proxy" for ice.proxy_only_if_behind_proxy.
                browser.navigate(origin + "/");
                Object states = browser.evaluate("async () => { const frame = document.createElement('iframe');"
                        + " document.body.append(frame);"
                        + " const run = w => new Promise(resolve => { let finished = false;"
                        + " const pc = new w.RTCPeerConnection({iceServers:[{urls:'stun:" + lan.getHostAddress()
                        + ":" + stun.getLocalPort() + "'}]}); pc.createDataChannel('x');"
                        + " const done = () => { if (finished) return; finished = true;"
                        + " const state = pc.iceGatheringState; pc.close(); resolve(state); };"
                        + " pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') done(); };"
                        + " pc.createOffer().then(o => pc.setLocalDescription(o)); setTimeout(done, 8000); });"
                        + " return Promise.all([run(window), run(frame.contentWindow)]); }");
                // Not vacuous: both windows finished gathering before UDP is checked.
                assertEquals(List.of("complete", "complete"), states);
                stun.setSoTimeout(1_000);
                assertThrows(SocketTimeoutException.class, () -> stun.receive(new DatagramPacket(new byte[512], 512)),
                        "WebRTC must not send UDP around the proxy");
            }

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
