package com.aresstack.argos.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** A CIDR block such as {@code 10.0.0.0/8} or {@code fc00::/7}; a bare address is a single-host block. */
public record IpRange(byte[] network, int prefix) {

    public IpRange {
        network = network.clone();
        if (prefix < 0 || prefix > network.length * 8) {
            throw new IllegalArgumentException("Invalid prefix length: " + prefix);
        }
    }

    public static IpRange parse(String text) {
        String value = text.trim();
        if (value.startsWith("[") && value.contains("]")) {
            value = value.substring(1, value.indexOf(']')) + value.substring(value.indexOf(']') + 1);
        }
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        if (!isAddressLiteral(address)) throw new IllegalArgumentException("Not an IP address: " + text);
        try {
            // Literal addresses only: getByName does not query DNS for them.
            byte[] raw = InetAddress.getByName(address).getAddress();
            int prefix = slash < 0 ? raw.length * 8 : Integer.parseInt(value.substring(slash + 1));
            return new IpRange(raw, prefix);
        } catch (UnknownHostException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid network range: " + text, invalid);
        }
    }

    /** Dotted IPv4 or IPv6 text; never a host name, so parsing cannot trigger a DNS lookup. */
    public static boolean isAddressLiteral(String text) {
        return text.matches("\\d{1,3}(\\.\\d{1,3}){3}") || (text.contains(":") && text.matches("[0-9A-Fa-f:.%]+"));
    }

    public boolean contains(InetAddress address) {
        return contains(address.getAddress());
    }

    public boolean contains(byte[] raw) {
        if (raw.length != network.length) return false;
        for (int bit = 0; bit < prefix; bit++) {
            int mask = 0x80 >> (bit % 8);
            if ((network[bit / 8] & mask) != (raw[bit / 8] & mask)) return false;
        }
        return true;
    }

    @Override
    public byte[] network() {
        return network.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IpRange range && range.prefix == prefix && Arrays.equals(range.network, network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefix;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
