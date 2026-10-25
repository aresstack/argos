package com.aresstack.argos.network;

import java.net.URI;
import java.util.Locale;

/**
 * A proxy that Argos' local proxy forwards to, e.g. the Camoufox geo/IP proxy. {@code socks5://host:port}
 * or {@code http://host:port} (HTTP CONNECT); credentials from the URL or from launch.json.
 */
public record UpstreamProxy(Kind kind, String host, int port, String username, String password) {
    public enum Kind { SOCKS5, HTTP }

    public static UpstreamProxy parse(String server, String username, String password) {
        String value = server.trim();
        if (!value.contains("://")) value = "http://" + value;
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            // No cause: URI's own message repeats the input, credentials included.
            throw new IllegalArgumentException("Invalid upstream proxy: " + redacted(server));
        }
        if (uri.getScheme() == null) throw new IllegalArgumentException("Invalid upstream proxy: " + redacted(server));
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        Kind kind = switch (scheme) {
            case "socks5", "socks5h", "socks" -> Kind.SOCKS5;
            case "http" -> Kind.HTTP;
            default -> throw new IllegalArgumentException(
                    "Upstream proxy must be socks5:// or http://, not " + scheme + "://");
        };
        if (uri.getHost() == null || uri.getPort() < 1) {
            throw new IllegalArgumentException("Upstream proxy needs host and port: " + redacted(server));
        }
        String user = username;
        String secret = password;
        if (uri.getUserInfo() != null) {
            int colon = uri.getUserInfo().indexOf(':');
            user = colon < 0 ? uri.getUserInfo() : uri.getUserInfo().substring(0, colon);
            secret = colon < 0 ? "" : uri.getUserInfo().substring(colon + 1);
        }
        String host = uri.getHost().startsWith("[") ? uri.getHost().substring(1, uri.getHost().length() - 1) : uri.getHost();
        return new UpstreamProxy(kind, host, uri.getPort(),
                user == null || user.isEmpty() ? null : user, secret == null ? "" : secret);
    }

    /** The proxy text with any {@code user:password@} part masked, for error messages. */
    static String redacted(String server) {
        String text = server.trim();
        int scheme = text.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int at = text.lastIndexOf('@');
        return at < start ? text : text.substring(0, start) + "***@" + text.substring(at + 1);
    }

    public boolean authenticated() {
        return username != null;
    }

    /** Never prints credentials. */
    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + "://" + host + ":" + port;
    }
}
