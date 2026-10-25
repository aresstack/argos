package com.aresstack.argos.search;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Host and registrable-domain arithmetic for link classification (no network, no public-suffix download). */
final class DomainNames {
    /** Common multi-label public suffixes; everything else is treated as a single-label suffix. */
    private static final Set<String> MULTI_LABEL_SUFFIXES = Set.of(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp",
            "ne.jp", "or.jp", "com.br", "com.cn", "com.tw", "com.hk", "co.in", "co.kr", "com.mx",
            "com.ar", "com.tr", "co.za", "com.sg", "github.io");

    private DomainNames() {
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    static String registrableDomain(String host) {
        if (host == null || host.isEmpty()) return "";
        if (host.matches("[0-9.]+") || host.contains(":")) return host; // IP literal
        String[] labels = host.split("\\.");
        if (labels.length <= 2) return host;
        String lastTwo = labels[labels.length - 2] + "." + labels[labels.length - 1];
        if (MULTI_LABEL_SUFFIXES.contains(lastTwo)) {
            return labels[labels.length - 3] + "." + lastTwo;
        }
        return lastTwo;
    }

    /** Relation of a link target to the page it was found on. */
    static DomainRelation classify(String targetUrl, String pageUrl) {
        String target = hostOf(targetUrl);
        String page = hostOf(pageUrl);
        if (target.isEmpty()) return DomainRelation.ACTION;
        if (target.equals(page)) return DomainRelation.SAME_HOST;
        if (target.endsWith("." + page)) return DomainRelation.SUBDOMAIN;
        if (registrableDomain(target).equals(registrableDomain(page))) return DomainRelation.SAME_REGISTRABLE_DOMAIN;
        return DomainRelation.EXTERNAL;
    }

    /** True when the host is the domain itself or one of its subdomains. */
    static boolean isWithin(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    enum DomainRelation { SAME_HOST, SUBDOMAIN, SAME_REGISTRABLE_DOMAIN, EXTERNAL, ACTION }
}
