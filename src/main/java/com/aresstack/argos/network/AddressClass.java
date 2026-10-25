package com.aresstack.argos.network;

import java.net.InetAddress;
import java.util.List;

/**
 * Non-public address space the browser must not reach by default. {@link #intranet()} classes may be
 * admitted by a host rule; the others only by a range rule that names them, and {@link #UNSPECIFIED}
 * and {@link #MULTICAST} never (0.0.0.0 reaches the local machine on Linux and macOS).
 */
public enum AddressClass {
    UNSPECIFIED(false, "0.0.0.0/8", "::/128"),
    LOOPBACK(false, "127.0.0.0/8", "::1/128"),
    METADATA(false, "169.254.169.254/32", "fd00:ec2::254/128", "100.100.100.200/32"),
    LINK_LOCAL(false, "169.254.0.0/16", "fe80::/10"),
    PRIVATE(true, "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fec0::/10"),
    UNIQUE_LOCAL(true, "fc00::/7"),
    SHARED_CGNAT(true, "100.64.0.0/10"),
    RESERVED(false, "192.0.0.0/24", "198.18.0.0/15", "240.0.0.0/4", "255.255.255.255/32",
            "64:ff9b:1::/48", "100::/64", "2001:db8::/32"),
    MULTICAST(false, "224.0.0.0/4", "ff00::/8"),
    /** An address of one of this machine's own interfaces; set by {@link NetworkPolicy}, not by range. */
    LOCAL_INTERFACE(false);

    private static final IpRange NAT64 = IpRange.parse("64:ff9b::/96");
    private static final IpRange SIX_TO_FOUR = IpRange.parse("2002::/16");
    private static final IpRange IPV4_COMPATIBLE = IpRange.parse("::/96");
    // Built from bytes: Java turns the text form ::ffff:a.b.c.d into a plain IPv4 address.
    private static final IpRange IPV4_MAPPED = new IpRange(
            new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0}, 96);
    // SIIT IPv4-translated form ::ffff:0:a.b.c.d (RFC 2765).
    private static final IpRange IPV4_TRANSLATED = new IpRange(
            new byte[]{0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0, 0, 0}, 96);

    private final boolean intranet;
    private final List<IpRange> ranges;

    AddressClass(boolean intranet, String... ranges) {
        this.intranet = intranet;
        this.ranges = List.of(ranges).stream().map(IpRange::parse).toList();
    }

    /** Whether a host rule (without an explicit range) may admit this class, e.g. a wiki at 10.1.2.3. */
    public boolean intranet() {
        return intranet;
    }

    /** Whether no rule at all can admit this class. */
    public boolean neverAllowed() {
        return this == UNSPECIFIED || this == MULTICAST;
    }

    /** The first matching class, or {@code null} for public addresses. Embedded IPv4 counts as IPv4. */
    public static AddressClass of(InetAddress address) {
        return of(address.getAddress());
    }

    static AddressClass of(byte[] raw) {
        if (raw.length == 16) {
            byte[] embedded = embeddedIpv4(raw);
            if (embedded != null) return of(embedded);
        }
        for (AddressClass candidate : values()) {
            for (IpRange range : candidate.ranges) {
                if (range.contains(raw)) return candidate;
            }
        }
        return null;
    }

    /** IPv4 hidden in IPv6 forms that route to it: mapped, translated, compatible, NAT64 and 6to4. */
    private static byte[] embeddedIpv4(byte[] raw) {
        if (IPV4_MAPPED.contains(raw) || IPV4_TRANSLATED.contains(raw) || NAT64.contains(raw)) {
            return new byte[]{raw[12], raw[13], raw[14], raw[15]};
        }
        if (IPV4_COMPATIBLE.contains(raw)) {
            // ::/128 and ::1/128 are IPv6 themselves; everything else in ::/96 is deprecated compatible form.
            boolean unspecifiedOrLoopback = raw[12] == 0 && raw[13] == 0 && raw[14] == 0 && (raw[15] == 0 || raw[15] == 1);
            return unspecifiedOrLoopback ? null : new byte[]{raw[12], raw[13], raw[14], raw[15]};
        }
        if (SIX_TO_FOUR.contains(raw)) return new byte[]{raw[2], raw[3], raw[4], raw[5]};
        return null;
    }
}
