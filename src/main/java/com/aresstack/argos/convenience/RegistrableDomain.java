package com.aresstack.argos.convenience;

import com.google.common.net.InternetDomainName;

import java.net.URI;
import java.util.Locale;

/**
 * The registrable domain of a URL by the Public Suffix List (Guava's bundled copy): {@code www.example.com} and
 * {@code docs.example.com} share {@code example.com}, {@code a.github.io} and {@code b.github.io} do not. IP
 * literals, {@code localhost} and hosts without a known public suffix stand for themselves.
 */
public final class RegistrableDomain {
    private RegistrableDomain() {
    }

    public static String host(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    public static String of(String url) {
        return ofHost(host(url));
    }

    public static String ofHost(String host) {
        if (host == null || host.isEmpty()) return "";
        if (host.contains(":") || host.matches("[0-9.]+")) return host;
        try {
            InternetDomainName name = InternetDomainName.from(host);
            if (name.isUnderPublicSuffix()) return name.topPrivateDomain().toString();
            return host;
        } catch (IllegalArgumentException | IllegalStateException notADomain) {
            return host;
        }
    }

    /** The host the model is shown: lower case, without a leading {@code www.}. */
    public static String display(String url) {
        String host = host(url);
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
