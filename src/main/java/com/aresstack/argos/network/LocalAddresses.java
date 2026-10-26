package com.aresstack.argos.network;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The addresses of this machine's own interfaces, e.g. a public IPv4 or a global IPv6 address. A
 * connection to one of them reaches services bound to all interfaces without passing the host
 * firewall, so the policy treats them like loopback. Refreshed every few seconds for DHCP/SLAAC.
 *
 * <p>Connection handlers are virtual threads; a monitor held during the native interface enumeration
 * would pin their carriers (slow on Windows with many adapters). So only the first answer is awaited,
 * later refreshes are done by one caller while the others keep reading the previous snapshot.
 */
final class LocalAddresses implements Supplier<Set<InetAddress>> {
    static final LocalAddresses SYSTEM = new LocalAddresses();
    private static final long REFRESH_NANOS = 10_000_000_000L;

    private volatile Set<InetAddress> cached;
    private final AtomicLong refreshDue = new AtomicLong();
    private final AtomicBoolean warned = new AtomicBoolean();

    @Override
    public Set<InetAddress> get() {
        Set<InetAddress> current = cached;
        if (current == null) return first();
        long due = refreshDue.get();
        long now = System.nanoTime();
        if (now - due >= 0 && refreshDue.compareAndSet(due, now + REFRESH_NANOS)) {
            current = load(current);
            cached = current;
        }
        return current;
    }

    private synchronized Set<InetAddress> first() {
        if (cached == null) {
            cached = load(Set.of());
            refreshDue.set(System.nanoTime() + REFRESH_NANOS);
        }
        return cached;
    }

    private Set<InetAddress> load(Set<InetAddress> previous) {
        Set<InetAddress> addresses = new HashSet<>();
        try {
            for (NetworkInterface network : NetworkInterface.networkInterfaces().toList()) {
                for (InetAddress address : network.inetAddresses().toList()) {
                    // Without scope: an address from SOCKS carries none.
                    addresses.add(InetAddress.getByAddress(address.getAddress()));
                }
            }
            return Set.copyOf(addresses);
        } catch (SocketException | UnknownHostException unavailable) {
            // Keep the previous answer; loopback and private space are blocked by class anyway.
            if (previous.isEmpty() && warned.compareAndSet(false, true)) {
                System.err.println("Argos network policy: cannot list this machine's interface addresses ("
                        + unavailable.getMessage() + "); its own public addresses are not blocked until that works");
            }
            return previous;
        }
    }
}
