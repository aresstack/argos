package com.aresstack.argos.web;

import org.junit.jupiter.api.Test;

import com.aresstack.argos.network.HostResolver;
import com.aresstack.argos.network.NetworkPolicy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlPolicyIntranetTest {
    @Test
    void blocksPrivateTargetsByDefault() {
        UrlPolicy strict = UrlPolicy.publicOnly();
        assertThrows(UrlPolicy.BlockedUrlException.class, () -> strict.check("http://127.0.0.1:8085/"));
        assertThrows(UrlPolicy.BlockedUrlException.class, () -> strict.check("http://192.168.1.1/"));
        assertThrows(UrlPolicy.BlockedUrlException.class, () -> strict.check("http://[::1]/"));
        assertThrows(UrlPolicy.BlockedUrlException.class, () -> strict.check("http://169.254.169.254/latest"));
    }

    @Test
    void admitsExplicitIntranetHostsAndNetworksOnly() {
        // Host rules are decided on the resolved addresses, exactly as the proxy decides them.
        HostResolver dns = host -> switch (host) {
            case "wiki.firma.local" -> List.of(InetAddress.getByName("10.9.0.7"));
            case "build.lab.firma.local" -> List.of(InetAddress.getByName("172.16.4.2"));
            case "rebound.lab.firma.local" -> List.of(InetAddress.getByName("127.0.0.1"));
            default -> throw new UnknownHostException(host);
        };
        UrlPolicy intranet = new UrlPolicy(NetworkPolicy.parse(
                "wiki.firma.local,*.lab.firma.local,10.20.0.0/16,192.168.1.5,fd00::/8"), dns);
        assertTrue(intranet.permits("http://wiki.firma.local/start"));
        assertTrue(intranet.permits("https://build.lab.firma.local/"));
        assertTrue(intranet.permits("http://10.20.3.4/"));
        assertTrue(intranet.permits("http://192.168.1.5/"));
        assertTrue(intranet.permits("http://[fd12::1]/"));
        assertFalse(intranet.permits("http://10.21.0.1/"));
        assertFalse(intranet.permits("http://192.168.1.6/"));
        assertFalse(intranet.permits("http://127.0.0.1/"));
        assertFalse(intranet.permits("ftp://wiki.firma.local/"));
        assertFalse(intranet.permits("http://rebound.lab.firma.local/"), "a host rule never admits loopback");
        assertFalse(intranet.permits("http://unknown.firma.local/"), "unresolvable, as the proxy would refuse it");
    }

    @Test
    void rejectsMalformedRules() {
        assertThrows(IllegalArgumentException.class, () -> UrlPolicy.allowing("10.0.0.0/33"));
    }
}
