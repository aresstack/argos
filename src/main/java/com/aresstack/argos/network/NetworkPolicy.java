package com.aresstack.argos.network;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

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
    /** A decision together with the addresses it was made on; empty when the host did not resolve. */
    public record Resolution(Decision decision, List<InetAddress> addresses) {
        public Resolution {
            addresses = List.copyOf(addresses);
        }
    }

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
    private final Supplier<Set<InetAddress>> localAddresses;

    private NetworkPolicy(List<Rule> rules, List<String> text, Supplier<Set<InetAddress>> localAddresses) {
        this.rules = List.copyOf(rules);
        this.text = List.copyOf(text);
        this.localAddresses = localAddresses;
    }

    public static NetworkPolicy publicOnly() {
        return new NetworkPolicy(List.of(), List.of(), LocalAddresses.SYSTEM);
    }

    /** The same rules with another source of this machine's interface addresses; for tests. */
    public NetworkPolicy withLocalAddresses(Supplier<Set<InetAddress>> addresses) {
        return new NetworkPolicy(rules, text, addresses);
    }

    /**
     * Whether a rule admits loopback. Only for tests against local fixtures: the browser could then
     * reach every loopback service, including Playwright's and Firefox' own ports.
     */
    public boolean admitsLoopback() {
        return rules.stream().anyMatch(rule -> rule.range != null && (rule.range.contains(LOOPBACK_V4)
                || rule.range.contains(LOOPBACK_V6)));
    }

    private static final byte[] LOOPBACK_V4 = {127, 0, 0, 1};
    private static final byte[] LOOPBACK_V6 = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

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
        return new NetworkPolicy(rules, text, LocalAddresses.SYSTEM);
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
            AddressClass kind = classify(address);
            if (kind == null || admitted(host, address, kind)) continue;
            return Decision.blocked(kind.name().toLowerCase(Locale.ROOT).replace('_', '-')
                    + " address " + address.getHostAddress()
                    + (kind.neverAllowed() ? "" : " (allow it explicitly with ARGOS_INTRANET_ALLOW)"));
        }
        return Decision.ALLOWED;
    }

    /**
     * Resolves {@code requestedHost} once (IP literals without DNS, {@code .invalid} never) and checks
     * the answer. The proxy enforces with this; callers then connect only to {@code addresses()}.
     */
    public Resolution resolve(String requestedHost, HostResolver resolver) {
        String host = normalizeHost(requestedHost);
        if (host.equals("invalid") || host.endsWith(".invalid")) {
            return new Resolution(Decision.blocked("reserved name, not resolved"), List.of()); // RFC 6761
        }
        List<InetAddress> addresses;
        try {
            addresses = IpRange.isAddressLiteral(host) ? List.of(InetAddress.getByName(host)) : resolver.resolve(host);
        } catch (UnknownHostException unknown) {
            return new Resolution(Decision.blocked("DNS resolution failed"), List.of());
        }
        return new Resolution(check(host, addresses), addresses);
    }

    /**
     * Early, informative check of a host before navigating, e.g. for tool error messages. It resolves
     * independently of the browser and therefore protects nothing on its own; enforcement is the proxy.
     */
    public Decision checkHost(String host, HostResolver resolver) {
        return resolve(host, resolver).decision();
    }

    /**
     * {@link #checkHost} for an absolute http(s) URL.
     *
     * @throws IllegalArgumentException if the URL is malformed, not http(s) or has no host
     */
    public Decision checkUrl(String url, HostResolver resolver) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Empty URL.");
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http(s) URLs can be opened, e.g. https://example.org/");
        }
        String host = uri.getHost();
        if (host == null && uri.getRawAuthority() != null) {
            // java.net.URI leaves the host empty for names such as my_host.example; browsers accept them.
            String authority = uri.getRawAuthority().substring(uri.getRawAuthority().lastIndexOf('@') + 1);
            host = authority.startsWith("[") ? authority.substring(0, authority.indexOf(']') + 1)
                    : authority.contains(":") ? authority.substring(0, authority.indexOf(':')) : authority;
        }
        if (host == null || host.isBlank()) throw new IllegalArgumentException("URL has no host: " + url);
        return checkHost(host, resolver);
    }

    /** {@link AddressClass#of}, plus {@link AddressClass#LOCAL_INTERFACE} for this machine's addresses. */
    public AddressClass classify(InetAddress address) {
        AddressClass kind = AddressClass.of(address);
        if (kind != null) return kind;
        try {
            InetAddress plain = InetAddress.getByAddress(address.getAddress());
            return localAddresses.get().contains(plain) ? AddressClass.LOCAL_INTERFACE : null;
        } catch (UnknownHostException impossible) {
            return null;
        }
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
