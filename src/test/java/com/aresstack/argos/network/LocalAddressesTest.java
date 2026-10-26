package com.aresstack.argos.network;

import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LocalAddressesTest {
    @Test
    void listsEveryInterfaceAddressWithoutScopeAndAnswersFromTheSnapshotInBetween() throws Exception {
        LocalAddresses local = new LocalAddresses();
        Set<InetAddress> expected = new HashSet<>();
        for (NetworkInterface network : NetworkInterface.networkInterfaces().toList()) {
            for (InetAddress address : network.inetAddresses().toList()) {
                expected.add(InetAddress.getByAddress(address.getAddress()));
            }
        }
        Set<InetAddress> first = local.get();
        assertEquals(expected, first);
        for (InetAddress address : first) {
            if (address instanceof Inet6Address scoped) assertEquals(0, scoped.getScopeId(), address.toString());
        }
        assertSame(first, local.get(), "within the refresh interval the snapshot is reused without a lock");
    }
}
