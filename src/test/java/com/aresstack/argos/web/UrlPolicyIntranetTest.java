package com.aresstack.argos.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlPolicyIntranetTest {
    @Test
    void blocksPrivateTargetsByDefault() {
        UrlPolicy strict = new UrlPolicy(false);
        assertThrows(UrlPolicy.BlockedUrlException.class, () -> strict.check("http://127.0.0.1:8085/"));
        assertThrows(UrlPolicy.BlockedUrlException.class, () -> strict.check("http://192.168.1.1/"));
        assertThrows(UrlPolicy.BlockedUrlException.class, () -> strict.check("http://[::1]/"));
        assertThrows(UrlPolicy.BlockedUrlException.class, () -> strict.check("http://169.254.169.254/latest"));
    }

    @Test
    void admitsExplicitIntranetHostsAndNetworksOnly() {
        UrlPolicy intranet = new UrlPolicy(false, List.of("wiki.firma.local", "*.lab.firma.local",
                " 10.20.0.0/16", "192.168.1.5", "fd00::/8"));
        assertTrue(intranet.permits("http://wiki.firma.local/start"));
        assertTrue(intranet.permits("https://build.lab.firma.local/"));
        assertTrue(intranet.permits("http://10.20.3.4/"));
        assertTrue(intranet.permits("http://192.168.1.5/"));
        assertTrue(intranet.permits("http://[fd12::1]/"));
        assertFalse(intranet.permits("http://10.21.0.1/"));
        assertFalse(intranet.permits("http://192.168.1.6/"));
        assertFalse(intranet.permits("http://127.0.0.1/"));
        assertFalse(intranet.permits("ftp://wiki.firma.local/"));
    }

    @Test
    void rejectsMalformedRules() {
        assertThrows(IllegalArgumentException.class, () -> new UrlPolicy(false, List.of("10.0.0.0/33")));
    }
}
