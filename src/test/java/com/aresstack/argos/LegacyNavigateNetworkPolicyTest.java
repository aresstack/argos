package com.aresstack.argos;

import com.aresstack.argos.network.NetworkSettings;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Angelo's decision (PR #6): the legacy profile keeps its nine tools and their signatures, but
 * {@code browser_navigate} is bound by the same network policy as every other tool. No legacy bypass.
 */
class LegacyNavigateNetworkPolicyTest {
    @TempDir Path temporary;

    @Test
    void legacyNavigateRefusesInternalTargetsBeforeTheBrowserStarts() {
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config(NetworkSettings.strict()))) {
            for (String internal : new String[]{"http://127.0.0.1:9/", "http://[::1]/", "http://192.168.1.1/",
                    "http://169.254.169.254/latest/meta-data/", "http://100.100.100.200/"}) {
                String message = text(navigate(browser, internal));
                assertTrue(message.contains("Blocked by the network policy"), internal + ": " + message);
            }
        }
    }

    @Test
    void legacyNavigateLetsAdmittedIntranetAndPublicTargetsThrough() {
        NetworkSettings intranet = NetworkSettings.parse("strict", "10.20.0.0/16", null, null);
        try (CamoufoxBrowser browser = new CamoufoxBrowser(config(intranet))) {
            // No browser is configured here, so an admitted target fails later, at launch, not at the policy.
            for (String admitted : new String[]{"http://10.20.0.5/", "http://93.184.215.14/"}) {
                String message = text(navigate(browser, admitted));
                assertFalse(message.contains("Blocked"), admitted + ": " + message);
            }
            assertTrue(text(navigate(browser, "http://10.21.0.5/")).contains("Blocked by the network policy"));
        }
    }

    /** Opt-in: the same three cases through the real proxy and Camoufox. */
    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void legacyNavigateInRealCamoufox() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("argos.testCamoufox"), "Opt-in real browser test");
        ArgosConfig local = ArgosConfig.fromEnvironment();
        local.requireBrowserConfiguration();
        byte[] html = "<html><head><title>Intranet wiki</title></head><body>internal page</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        site.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        site.start();
        String internal = "http://127.0.0.1:" + site.getAddress().getPort() + "/";
        try {
            try (CamoufoxBrowser strict = new CamoufoxBrowser(local(local, NetworkSettings.strict(), "strict"))) {
                assertTrue(navigate(strict, internal).isError(), "internal target blocked by default");
            }
            NetworkSettings admitted = NetworkSettings.parse("strict", "127.0.0.1", null, null);
            try (CamoufoxBrowser browser = new CamoufoxBrowser(local(local, admitted, "admitted"))) {
                CallToolResult wiki = navigate(browser, internal);
                assertFalse(wiki.isError(), text(wiki));
                assertTrue(text(wiki).contains("Intranet wiki"), text(wiki));
                Assumptions.assumeTrue(internetReachable(), "public target needs internet access");
                CallToolResult example = navigate(browser, "https://example.com/");
                assertFalse(example.isError(), text(example));
                assertTrue(text(example).contains("Example Domain"), text(example));
            }
        } finally {
            site.stop(0);
        }
    }

    private ArgosConfig config(NetworkSettings network) {
        return new ArgosConfig("127.0.0.1", 0, null, null, temporary, true).withNetwork(network);
    }

    private ArgosConfig local(ArgosConfig local, NetworkSettings network, String profile) {
        return new ArgosConfig("127.0.0.1", 0, local.executable(), local.launchOptions(),
                temporary.resolve(profile), true).withNetwork(network);
    }

    private static CallToolResult navigate(CamoufoxBrowser browser, String url) {
        SyncToolSpecification tool = new ArgosTools(browser).forProfile(ToolProfile.LEGACY).stream()
                .filter(spec -> spec.tool().name().equals("browser_navigate")).findFirst().orElseThrow();
        return tool.callHandler().apply(null, new CallToolRequest("browser_navigate", Map.of("url", url)));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private static boolean internetReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("example.com", 443), 3_000);
            return true;
        } catch (Exception offline) {
            return false;
        }
    }
}
