package com.aresstack.argos;

import com.aresstack.argos.page.ReadinessPolicy;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Environment-first configuration compatible with AresStack / Pyloros. */
public record ArgosConfig(
        String host, int port, Path executable, Path launchOptions, Path profile, boolean headless,
        String publicOrigin, String securityMode, String centralOauthIssuer, String requiredScope,
        ReadinessPolicy readiness) {

    public ArgosConfig {
        if (readiness == null) readiness = ReadinessPolicy.balanced();
    }

    public ArgosConfig(String host, int port, Path executable, Path launchOptions, Path profile, boolean headless,
                       String publicOrigin, String securityMode, String centralOauthIssuer, String requiredScope) {
        this(host, port, executable, launchOptions, profile, headless,
                publicOrigin, securityMode, centralOauthIssuer, requiredScope, ReadinessPolicy.balanced());
    }

    // Direct browser-test construction remains independent of server configuration.
    public ArgosConfig(String host, int port, Path executable, Path launchOptions,
                       Path profile, boolean headless) {
        this(host, port, executable, launchOptions, profile, headless,
                null, "central-oauth", null, "argos", ReadinessPolicy.balanced());
    }

    public static ArgosConfig fromEnvironment() throws IOException {
        Properties application = readProperties(Path.of(System.getProperty("argos.config",
                Path.of(System.getProperty("user.home"), ".argos", "argos.properties").toString())));
        // Reuse binary/fingerprint paths only. Never borrow GPT Desktop's browser profile.
        Properties desktop = readProperties(Path.of(System.getProperty("user.home"),
                ".browser-event-hub", "desktop", "camoufox.properties"));

        String portText = setting("SERVER_PORT", application, "server.port", null);
        if (portText == null) {
            throw new IllegalArgumentException(
                    "SERVER_PORT is required (set it in the AresStack service Environment tab).");
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException invalidPort) {
            throw new IllegalArgumentException("SERVER_PORT must be an integer: " + portText, invalidPort);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SERVER_PORT must be between 1 and 65535: " + port);
        }

        String host = setting("SERVER_HOST", application, "server.host", "127.0.0.1");
        if (!"127.0.0.1".equals(host) && !"::1".equals(host)) {
            throw new IllegalArgumentException(
                    "SERVER_HOST must be a loopback address (127.0.0.1 or ::1).");
        }
        String publicOrigin = normalizeOrigin(setting("PUBLIC_ORIGIN", application, "public.origin", null));
        String issuer = normalizeOrigin(setting("CENTRAL_OAUTH_ISSUER", application, "oauth.central-issuer", null));
        String securityMode = setting("SECURITY_MODE", application, "security.mode", "central-oauth")
                .toLowerCase(Locale.ROOT);
        String requiredScope = setting("REQUIRED_SCOPE", application, "security.required-scope", "argos");
        if (requiredScope == null || requiredScope.isBlank() || requiredScope.matches(".*\\s+.*")) {
            throw new IllegalArgumentException("REQUIRED_SCOPE must be a single nonblank OAuth scope.");
        }
        if (!"none".equals(securityMode) && !"central-oauth".equals(securityMode)) {
            throw new IllegalArgumentException("SECURITY_MODE must be 'central-oauth' or 'none'.");
        }
        if ("central-oauth".equals(securityMode)) {
            requireHttpsOrigin("PUBLIC_ORIGIN", publicOrigin);
            requireHttpsOrigin("CENTRAL_OAUTH_ISSUER", issuer);
        }

        String executable = setting("ARGOS_CAMOUFOX_EXECUTABLE", application, "camoufox.executable",
                desktop.getProperty("executable"));
        String options = setting("ARGOS_CAMOUFOX_LAUNCH_OPTIONS", application, "camoufox.launchOptions",
                desktop.getProperty("launchOptions"));
        String profile = setting("ARGOS_PROFILE_DIR", application, "browser.profile",
                Path.of(System.getProperty("user.home"), ".argos", "profiles", "default").toString());
        return new ArgosConfig(host, port,
                executable == null ? null : Path.of(executable).toAbsolutePath(),
                options == null ? null : Path.of(options).toAbsolutePath(),
                Path.of(profile).toAbsolutePath(),
                Boolean.parseBoolean(setting("ARGOS_HEADLESS", application, "browser.headless", "false")),
                publicOrigin, securityMode, issuer, requiredScope,
                ReadinessPolicy.from(name -> setting(name, application, propertyKey(name), null)));
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    private static String setting(String environment, Properties properties, String key, String fallback) {
        String env = System.getenv(environment);
        if (env != null && !env.isBlank()) return env.trim();
        String property = properties.getProperty(key);
        return property != null && !property.isBlank() ? property.trim() : fallback;
    }

    /** ARGOS_READY_DOM_STABLE_MS → ready.dom.stable.ms in argos.properties. */
    private static String propertyKey(String environment) {
        return environment.replaceFirst("^ARGOS_", "").toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private static String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) return null;
        String origin = value.trim();
        while (origin.endsWith("/")) origin = origin.substring(0, origin.length() - 1);
        return origin;
    }

    private static void requireHttpsOrigin(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required with SECURITY_MODE=central-oauth.");
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException(name + " must be an HTTPS origin, not a path: " + value);
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value, error);
        }
    }

    public void requireBrowserConfiguration() {
        if (executable == null || !Files.isRegularFile(executable)) {
            throw new IllegalStateException("Set ARGOS_CAMOUFOX_EXECUTABLE to a patched Camoufox executable.");
        }
        String browserName = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!browserName.equals("camoufox") && !browserName.equals("camoufox.exe")) {
            throw new IllegalArgumentException("Argos supports only Camoufox, not: " + browserName);
        }
        if (launchOptions == null || !Files.isRegularFile(launchOptions)) {
            throw new IllegalStateException("Set ARGOS_CAMOUFOX_LAUNCH_OPTIONS to official Camoufox launch.json.");
        }
    }
}
