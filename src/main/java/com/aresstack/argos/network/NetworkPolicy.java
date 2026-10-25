package com.aresstack.argos.network;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides per connection, on the resolved addresses, whether the browser may connect.
 *
 * <p>Rules use the {@code ARGOS_INTRANET_ALLOW} syntax of the web_* URL check, comma separated:
 * {@code wiki.firma.local} (exact host), {@code *.firma.local} (sub-domains), {@code 10.20.0.0/16} or
 * {@code 192.168.1.5} (resolved addresses), plus {@code wiki.firma.local@10.20.0.0/16} (host only at
 * that range). A host rule admits only intranet space (private, unique-local, CGNAT); loopback,
 * link-local and cloud metadata need a range rule that covers them.
 */
public final class NetworkPolicy {
    /** Outcome of one check; {@code reason} is null when allowed. */
    public record Decision(boolean allowed, String reason) {
        static final Decision ALLOWED = new Decision(true, null);

        static Decision blocked(String reason) {
            return new Decision(false, reason);
        }
    }

    private record Rule(String host, IpRange range) {
        boolean matchesHost(String candidate) {
            if (host == null) return true;
            return host.startsWith("*.") ? candidate.endsWith(host.substring(1)) : candidate.equals(host);
        }
    }

    private final List<Rule> rules;
    private final List<String> text;

    private NetworkPolicy(List<Rule> rules, List<String> text) {
        this.rules = List.copyOf(rules);
        this.text = List.copyOf(text);
    }

    public static NetworkPolicy publicOnly() {
        return new NetworkPolicy(List.of(), List.of());
    }

    public static NetworkPolicy parse(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) return publicOnly();
        List<Rule> rules = new ArrayList<>();
        List<String> text = new ArrayList<>();
        for (String raw : commaSeparated.split(",")) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            int at = entry.indexOf('@');
            String host = at < 0 ? entry : entry.substring(0, at);
            String range = at < 0 ? null : entry.substring(at + 1);
            if (at < 0 && IpRange.isAddressLiteral(entry.contains("/") ? entry.substring(0, entry.indexOf('/'))
                    : entry.replace("[", "").replace("]", ""))) {
                rules.add(new Rule(null, IpRange.parse(entry)));
            } else {
                if (!host.matches("(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")) {
                    throw new IllegalArgumentException("Invalid intranet rule: " + raw.trim());
                }
                rules.add(new Rule(host, range == null ? null : IpRange.parse(range)));
            }
            text.add(entry);
        }
        return new NetworkPolicy(rules, text);
    }

    /** The configured rules, normalized, for status output. */
    public List<String> rules() {
        return text;
    }

    /**
     * Every address must pass: one internal record is enough to reach an internal service, and the
     * proxy connects only to addresses checked here.
     */
    public Decision check(String requestedHost, List<InetAddress> addresses) {
        if (addresses.isEmpty()) return Decision.blocked("no address for " + requestedHost);
        String host = normalizeHost(requestedHost);
        for (InetAddress address : addresses) {
            AddressClass kind = AddressClass.of(address);
            if (kind == null || admitted(host, address, kind)) continue;
            return Decision.blocked(kind.name().toLowerCase(Locale.ROOT).replace('_', '-')
                    + " address " + address.getHostAddress()
                    + (kind.neverAllowed() ? "" : " (allow it explicitly with ARGOS_INTRANET_ALLOW)"));
        }
        return Decision.ALLOWED;
    }

    private boolean admitted(String host, InetAddress address, AddressClass kind) {
        if (kind.neverAllowed()) return false;
        for (Rule rule : rules) {
            if (!rule.matchesHost(host)) continue;
            if (rule.range != null ? rule.range.contains(address) : kind.intranet()) return true;
        }
        return false;
    }

    static String normalizeHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        return value;
    }
}
