package com.aresstack.argos.network;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPolicyTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    private static InetAddress raw(int... bytes) throws Exception {
        byte[] value = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) value[i] = (byte) bytes[i];
        return InetAddress.getByAddress(value);
    }

    @Test
    void classifiesInternalSpaceIncludingIpv4HiddenInIpv6() throws Exception {
        Map<String, AddressClass> expected = Map.ofEntries(
                Map.entry("127.0.0.1", AddressClass.LOOPBACK),
                Map.entry("127.8.9.10", AddressClass.LOOPBACK),
                Map.entry("::1", AddressClass.LOOPBACK),
                Map.entry("0.0.0.0", AddressClass.UNSPECIFIED),
                Map.entry("::", AddressClass.UNSPECIFIED),
                Map.entry("10.1.2.3", AddressClass.PRIVATE),
                Map.entry("172.31.255.1", AddressClass.PRIVATE),
                Map.entry("192.168.0.1", AddressClass.PRIVATE),
                Map.entry("169.254.169.254", AddressClass.METADATA),
                Map.entry("fd00:ec2::254", AddressClass.METADATA),
                Map.entry("169.254.10.10", AddressClass.LINK_LOCAL),
                Map.entry("fe80::1", AddressClass.LINK_LOCAL),
                Map.entry("fd12:3456::1", AddressClass.UNIQUE_LOCAL),
                Map.entry("100.64.0.1", AddressClass.SHARED_CGNAT),
                Map.entry("224.0.0.251", AddressClass.MULTICAST),
                Map.entry("64:ff9b::7f00:1", AddressClass.LOOPBACK),          // NAT64 of 127.0.0.1
                Map.entry("2002:a9fe:a9fe::1", AddressClass.METADATA),       // 6to4 of 169.254.169.254
                Map.entry("::10.0.0.1", AddressClass.PRIVATE));              // IPv4-compatible
        for (var entry : expected.entrySet()) {
            assertEquals(entry.getValue(), AddressClass.of(ip(entry.getKey())), entry.getKey());
        }
        // IPv4-mapped written as raw bytes, as a SOCKS5 IPv6 request would carry it.
        assertEquals(AddressClass.LOOPBACK, AddressClass.of(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xff, (byte) 0xff, 127, 0, 0, 1}));
        assertNull(AddressClass.of(ip("93.184.215.14")));
        assertNull(AddressClass.of(ip("2606:4700:4700::1111")));
        assertNull(AddressClass.of(ip("172.32.0.1")));
        assertNull(AddressClass.of(ip("100.128.0.1")));
    }

    @Test
    void publicOnlyBlocksAnyInternalRecordAmongPublicOnes() throws Exception {
        NetworkPolicy policy = NetworkPolicy.publicOnly();
        assertTrue(policy.check("example.org", List.of(ip("93.184.215.14"))).allowed());
        NetworkPolicy.Decision mixed = policy.check("rebind.example", List.of(ip("93.184.215.14"), ip("127.0.0.1")));
        assertFalse(mixed.allowed());
        assertTrue(mixed.reason().contains("loopback address 127.0.0.1"), mixed.reason());
        assertFalse(policy.check("metadata", List.of(ip("169.254.169.254"))).allowed());
        assertFalse(policy.check("empty", List.of()).allowed());
    }

    @Test
    void hostRulesAdmitIntranetButNeverLoopbackOrMetadata() throws Exception {
        NetworkPolicy policy = NetworkPolicy.parse(" wiki.firma.local , *.corp.example ");
        assertTrue(policy.check("WIKI.firma.local.", List.of(ip("10.1.2.3"))).allowed());
        assertTrue(policy.check("git.corp.example", List.of(ip("fd00::5"))).allowed());
        assertFalse(policy.check("corp.example", List.of(ip("10.1.2.3"))).allowed(), "wildcard is sub-domains only");
        assertFalse(policy.check("other.local", List.of(ip("10.1.2.3"))).allowed());
        assertFalse(policy.check("wiki.firma.local", List.of(ip("127.0.0.1"))).allowed());
        assertFalse(policy.check("wiki.firma.local", List.of(ip("169.254.169.254"))).allowed());
        assertEquals(List.of("wiki.firma.local", "*.corp.example"), policy.rules());
    }

    @Test
    void rangeRulesAdmitWhatTheyNameExceptUnspecifiedAndMulticast() throws Exception {
        NetworkPolicy policy = NetworkPolicy.parse("127.0.0.1,10.20.0.0/16,wiki.firma.local@192.168.5.0/24,[::1]");
        assertTrue(policy.check("localhost", List.of(ip("127.0.0.1"), ip("::1"))).allowed());
        assertFalse(policy.check("x", List.of(ip("127.0.0.2"))).allowed());
        assertTrue(policy.check("anything", List.of(ip("10.20.9.9"))).allowed());
        assertTrue(policy.check("wiki.firma.local", List.of(ip("192.168.5.7"))).allowed());
        assertFalse(policy.check("other.firma.local", List.of(ip("192.168.5.7"))).allowed());
        NetworkPolicy everything = NetworkPolicy.parse("0.0.0.0/0,::/0");
        assertTrue(everything.check("x", List.of(ip("127.0.0.2"))).allowed());
        assertFalse(everything.check("zero", List.of(ip("0.0.0.0"))).allowed());
        assertFalse(everything.check("mdns", List.of(ip("224.0.0.251"))).allowed());
    }

    @Test
    void earlyUrlCheckUsesTheSameRulesAsTheProxy() throws Exception {
        NetworkPolicy policy = NetworkPolicy.parse("wiki.firma.local");
        List<String> lookups = new java.util.ArrayList<>();
        HostResolver dns = host -> {
            lookups.add(host);
            return switch (host) {
                case "wiki.firma.local" -> List.of(InetAddress.getByName("10.1.2.3"));
                case "example.org", "my_host.example" -> List.of(InetAddress.getByName("93.184.215.14"));
                case "rebind.example" -> List.of(InetAddress.getByName("93.184.215.14"), InetAddress.getByName("192.168.0.1"));
                default -> throw new java.net.UnknownHostException(host);
            };
        };
        assertTrue(policy.checkUrl("https://example.org/path?q=1", dns).allowed());
        assertTrue(policy.checkUrl("http://WIKI.firma.local:8080/", dns).allowed());
        assertTrue(policy.checkUrl("https://my_host.example/", dns).allowed());
        assertFalse(policy.checkUrl("https://rebind.example/", dns).allowed());
        assertFalse(policy.checkUrl("http://127.0.0.1:8085/", dns).allowed());
        assertFalse(policy.checkUrl("http://[::ffff:169.254.169.254]/latest", dns).allowed());
        assertEquals("DNS resolution failed", policy.checkUrl("https://missing.example/", dns).reason());
        assertFalse(policy.checkHost("probe.invalid", dns).allowed());
        assertEquals(List.of("example.org", "wiki.firma.local", "my_host.example", "rebind.example", "missing.example"),
                lookups, "IP literals and .invalid never reach DNS");
        assertThrows(IllegalArgumentException.class, () -> policy.checkUrl("file:///etc/passwd", dns));
        assertThrows(IllegalArgumentException.class, () -> policy.checkUrl("https:///nohost", dns));
        assertThrows(IllegalArgumentException.class, () -> policy.checkUrl(" ", dns));
    }

    @Test
    void rejectsMalformedRules() {
        assertThrows(IllegalArgumentException.class, () -> NetworkPolicy.parse("10.0.0.0/40"));
        assertThrows(IllegalArgumentException.class, () -> NetworkPolicy.parse("http://wiki/"));
        assertThrows(IllegalArgumentException.class, () -> NetworkPolicy.parse("wiki@not-a-range"));
    }

    @Test
    void settingsDefaultToStrictAndRejectUnknownValues() {
        NetworkSettings defaults = NetworkSettings.parse(null, null, null, null);
        assertTrue(defaults.strictMode());
        assertEquals(NetworkSettings.UpstreamDns.LOCAL, defaults.upstreamDns());
        assertFalse(NetworkSettings.parse("OFF", null, null, null).strictMode());
        assertThrows(IllegalArgumentException.class, () -> NetworkSettings.parse("lenient", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> NetworkSettings.parse(null, null, null, "both"));
        assertThrows(IllegalArgumentException.class, () -> NetworkSettings.parse(null, null, "ftp://p:21", null));
    }

    @Test
    void upstreamProxyParsesCredentialsWithoutPrintingThem() {
        UpstreamProxy proxy = UpstreamProxy.parse("socks5://user:s3cret@proxy.example:1080", null, null);
        assertEquals(UpstreamProxy.Kind.SOCKS5, proxy.kind());
        assertEquals("user", proxy.username());
        assertEquals("s3cret", proxy.password());
        assertFalse(proxy.toString().contains("s3cret"));
        UpstreamProxy launch = UpstreamProxy.parse("proxy.example:8080", "u", "p");
        assertEquals(UpstreamProxy.Kind.HTTP, launch.kind());
        assertEquals(8080, launch.port());
        assertTrue(launch.authenticated());
    }

    @Test
    void firefoxPrefsForceProxyAndRemoteDnsWithoutChangingSpeculativeDefaults() {
        Map<String, Object> prefs = FirefoxNetworkPrefs.forLocalSocks(40123);
        assertEquals(1, prefs.get("network.proxy.type"));
        assertEquals("127.0.0.1", prefs.get("network.proxy.socks"));
        assertEquals(40123, prefs.get("network.proxy.socks_port"));
        assertEquals(5, prefs.get("network.proxy.socks_version"));
        assertEquals(true, prefs.get("network.proxy.socks5_remote_dns"));
        assertEquals(true, prefs.get("network.proxy.allow_hijacking_localhost"));
        assertEquals(false, prefs.get("network.proxy.failover_direct"));
        assertEquals("", prefs.get("network.proxy.no_proxies_on"));
        assertEquals(5, prefs.get("network.trr.mode"));
        // Page-observable through Resource Timing; Camoufox keeps the stock values.
        assertFalse(prefs.containsKey("network.dns.disablePrefetch"));
        assertFalse(prefs.containsKey("network.prefetch-next"));
        assertFalse(prefs.containsKey("network.http.speculative-parallel-limit"));
    }
}
