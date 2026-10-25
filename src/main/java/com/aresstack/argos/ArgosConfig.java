package com.aresstack.argos;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Explicit configuration; never borrow an existing GPT Desktop browser profile. */
public record ArgosConfig(String host, int port, Path executable, Path launchOptions,
                          Path profile, boolean headless) {

    public static ArgosConfig fromEnvironment() throws IOException {
        Properties p = new Properties();
        Path config = Path.of(System.getProperty("argos.config",
                Path.of(System.getProperty("user.home"), ".argos", "argos.properties").toString()));
        if (Files.isRegularFile(config)) {
            try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }
        // Reuse the existing verified executable + launch.json only as *configuration defaults*.
        // Browser profile is always strictly Argos-owned.
        Properties desktop = new Properties();
        Path desktopFile = Path.of(System.getProperty("user.home"), ".browser-event-hub",
                "desktop", "camoufox.properties");
        if (Files.isRegularFile(desktopFile)) {
            try (Reader reader = Files.newBufferedReader(desktopFile, StandardCharsets.UTF_8)) {
                desktop.load(reader);
            }
        }
        String executable = setting("ARGOS_CAMOUFOX_EXECUTABLE", p, "camoufox.executable",
                desktop.getProperty("executable"));
        String options = setting("ARGOS_CAMOUFOX_LAUNCH_OPTIONS", p, "camoufox.launchOptions",
                desktop.getProperty("launchOptions"));
        String profile = setting("ARGOS_PROFILE_DIR", p, "browser.profile",
                Path.of(System.getProperty("user.home"), ".argos", "profiles", "default").toString());
        String port = setting("ARGOS_PORT", p, "server.port", "8082");
        String host = setting("ARGOS_HOST", p, "server.host", "127.0.0.1");
        if (!"127.0.0.1".equals(host) && !"::1".equals(host)) {
            throw new IllegalArgumentException("Argos binds to loopback only. Use the authenticated AresStack proxy.");
        }
        int parsedPort = Integer.parseInt(port);
        if (parsedPort < 0 || parsedPort > 65535) {
            throw new IllegalArgumentException("Invalid Argos port: " + port);
        }
        return new ArgosConfig(host, parsedPort,
                executable == null ? null : Path.of(executable).toAbsolutePath(),
                options == null ? null : Path.of(options).toAbsolutePath(),
                Path.of(profile).toAbsolutePath(),
                Boolean.parseBoolean(setting("ARGOS_HEADLESS", p, "browser.headless", "false")));
    }

    private static String setting(String environment, Properties properties,
                                  String key, String fallback) {
        String value = System.getenv(environment);
        if (value == null || value.isBlank()) {
            value = properties.getProperty(key, fallback);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    public void requireBrowserConfiguration() {
        if (executable == null || !Files.isRegularFile(executable)) {
            throw new IllegalStateException("Set ARGOS_CAMOUFOX_EXECUTABLE to a patched Camoufox executable.");
        }
        String browserName = executable.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!browserName.equals("camoufox") && !browserName.equals("camoufox.exe")) {
            throw new IllegalArgumentException("Argos supports only Camoufox, not: " + browserName);
        }
        if (launchOptions == null || !Files.isRegularFile(launchOptions)) {
            throw new IllegalStateException("Set ARGOS_CAMOUFOX_LAUNCH_OPTIONS to official Camoufox launch.json.");
        }
    }
}
