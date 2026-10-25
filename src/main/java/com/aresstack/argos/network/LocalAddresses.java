package com.aresstack.argos.network;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The addresses of this machine's own interfaces, e.g. a public IPv4 or a global IPv6 address. A
 * connection to one of them reaches services bound to all interfaces without passing the host
 * firewall, so the policy treats them like loopback. Refreshed every few seconds for DHCP/SLAAC.
 */
final class LocalAddresses implements Supplier<Set<InetAddress>> {
    static final LocalAddresses SYSTEM = new LocalAddresses();
    private static final long REFRESH_NANOS = 10_000_000_000L;

    private Set<InetAddress> cached = Set.of();
    private long loadedAt;
    private boolean loaded;

    @Override
    public synchronized Set<InetAddress> get() {
        long now = System.nanoTime();
        if (loaded && now - loadedAt < REFRESH_NANOS) return cached;
        Set<InetAddress> addresses = new HashSet<>();
        try {
            var interfaces = NetworkInterface.networkInterfaces().toList();
            for (NetworkInterface network : interfaces) {
                for (InetAddress address : network.inetAddresses().toList()) {
                    // Without scope: an address from SOCKS carries none.
                    addresses.add(InetAddress.getByAddress(address.getAddress()));
                }
            }
            cached = Set.copyOf(addresses);
        } catch (SocketException | UnknownHostException unavailable) {
            // Keep the previous answer; loopback and private space are blocked by class anyway.
        }
        loaded = true;
        loadedAt = now;
        return cached;
    }
}
