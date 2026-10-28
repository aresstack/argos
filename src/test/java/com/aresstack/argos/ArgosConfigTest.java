package com.aresstack.argos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgosConfigTest {
    @TempDir
    Path userHome;

    @Test
    void ignoresBrowserEventHubConfiguration() throws IOException {
        Path foreignConfig = userHome.resolve(".browser-event-hub")
                .resolve("desktop").resolve("camoufox.properties");
        Files.createDirectories(foreignConfig.getParent());
        Files.writeString(foreignConfig,
                "executable=" + propertyPath(userHome.resolve("foreign/camoufox.exe")) + System.lineSeparator()
                        + "launchOptions=" + propertyPath(userHome.resolve("foreign/launch.json"))
                        + System.lineSeparator());

        Path argosConfig = createArgosConfig("server.port=8085" + System.lineSeparator()
                + "security.mode=none" + System.lineSeparator());

        ArgosConfig config = ArgosConfig.fromEnvironment(Map.of(), userHome, argosConfig);

        assertNull(config.executable());
        assertNull(config.launchOptions());
    }

    @Test
    void readsBrowserPathsFromArgosOwnedConfiguration() throws IOException {
        Path executable = userHome.resolve("argos/camoufox.exe");
        Path launchOptions = userHome.resolve("argos/launch.json");
        Path argosConfig = createArgosConfig("server.port=8085" + System.lineSeparator()
                + "security.mode=none" + System.lineSeparator()
                + "camoufox.executable=" + propertyPath(executable) + System.lineSeparator()
                + "browser.launch-config=" + propertyPath(launchOptions) + System.lineSeparator());

        ArgosConfig config = ArgosConfig.fromEnvironment(Map.of(), userHome, argosConfig);

        assertNull(config.executable(), "the executable always comes from the managed installation");
        assertEquals(launchOptions.toAbsolutePath(), config.launchOptions());
    }

    @Test
    void environmentVariablesOverrideArgosOwnedConfiguration() throws IOException {
        Path configuredExecutable = userHome.resolve("argos/configured-camoufox.exe");
        Path configuredLaunchOptions = userHome.resolve("argos/configured-launch.json");
        Path environmentExecutable = userHome.resolve("environment/camoufox.exe");
        Path environmentLaunchOptions = userHome.resolve("environment/launch.json");
        Path argosConfig = createArgosConfig("server.port=8085" + System.lineSeparator()
                + "security.mode=none" + System.lineSeparator()
                + "camoufox.executable=" + propertyPath(configuredExecutable) + System.lineSeparator()
                + "browser.launch-config=" + propertyPath(configuredLaunchOptions) + System.lineSeparator());

        ArgosConfig config = ArgosConfig.fromEnvironment(Map.of(
                "ARGOS_CAMOUFOX_EXECUTABLE", environmentExecutable.toString(),
                "BROWSER_LAUNCH_CONFIG", environmentLaunchOptions.toString(),
                "SERVER_PORT", "8086"
        ), userHome, argosConfig);

        assertNull(config.executable(), "ARGOS_CAMOUFOX_EXECUTABLE is gone");
        assertEquals(environmentLaunchOptions.toAbsolutePath(), config.launchOptions());
        assertEquals(8086, config.port());

        // Property applies without the variable; the old names are gone and change nothing.
        ArgosConfig fromProperty = ArgosConfig.fromEnvironment(Map.of(
                "ARGOS_CAMOUFOX_LAUNCH_OPTIONS", environmentLaunchOptions.toString(),
                "CAMOUFOX_LAUNCH_OPTIONS", environmentLaunchOptions.toString()), userHome, argosConfig);
        assertEquals(configuredLaunchOptions.toAbsolutePath(), fromProperty.launchOptions());

        Path plainConfig = createArgosConfig("server.port=8085" + System.lineSeparator()
                + "security.mode=none" + System.lineSeparator()
                + "camoufox.launchOptions=" + propertyPath(configuredLaunchOptions) + System.lineSeparator());
        assertNull(ArgosConfig.fromEnvironment(Map.of(), userHome, plainConfig).launchOptions(),
                "without BROWSER_LAUNCH_CONFIG Argos uses its built-in profile");
    }

    @Test
    void startsWithoutBrowserPathsAndRejectsMissingExplicitFiles() throws IOException {
        Path argosConfig = createArgosConfig("server.port=8085" + System.lineSeparator()
                + "security.mode=none" + System.lineSeparator());
        ArgosConfig config = ArgosConfig.fromEnvironment(Map.of(), userHome, argosConfig);
        config.requireBrowserConfiguration(); // no paths: Argos installs and configures its own Camoufox

        ArgosConfig missingLaunch = ArgosConfig.fromEnvironment(Map.of("BROWSER_LAUNCH_CONFIG",
                userHome.resolve("absent/launch.json").toString()), userHome, argosConfig);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                missingLaunch::requireBrowserConfiguration);
        assertTrue(error.getMessage().contains("does not exist"));
    }

    @Test
    void browserInstallationDefaultsToArgosHomeAndEnvironmentWins() {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("browser.install-dir", userHome.resolve("configured").toString());
        properties.setProperty("camoufox.distributionUrl", "https://mirror.example/configured/");

        ArgosConfig.BrowserInstallation defaults = ArgosConfig.BrowserInstallation.fromEnvironment(
                Map.of(), userHome, new java.util.Properties());
        assertEquals(userHome.resolve(".argos").resolve("browser").toAbsolutePath(), defaults.root());
        assertNull(defaults.mirror());

        ArgosConfig.BrowserInstallation configured = ArgosConfig.BrowserInstallation.fromEnvironment(
                Map.of(), userHome, properties);
        assertEquals(userHome.resolve("configured").toAbsolutePath(), configured.root());

        ArgosConfig.BrowserInstallation environment = ArgosConfig.BrowserInstallation.fromEnvironment(
                Map.of("BROWSER_DIR", userHome.resolve("env").toString(),
                        "ARGOS_CAMOUFOX_DISTRIBUTION_URL", "https://mirror.example/env/"), userHome, properties);
        assertEquals(userHome.resolve("env").toAbsolutePath(), environment.root());
        assertEquals("https://mirror.example/env/", environment.mirror().toString());

        // The old prefixed name is gone: it neither overrides the property nor the default.
        ArgosConfig.BrowserInstallation legacy = ArgosConfig.BrowserInstallation.fromEnvironment(
                Map.of("ARGOS_BROWSER_DIR", userHome.resolve("legacy").toString()), userHome, properties);
        assertEquals(userHome.resolve("configured").toAbsolutePath(), legacy.root());
    }

    @Test
    void browserDirKeepsSpacesAndWindowsDriveLetters() {
        Path spaced = userHome.resolve("Other Drive").resolve("Argos Browser");
        ArgosConfig.BrowserInstallation explicit = ArgosConfig.BrowserInstallation.fromEnvironment(
                Map.of("BROWSER_DIR", spaced.toString()), userHome, new java.util.Properties());
        assertEquals(spaced.toAbsolutePath(), explicit.root());

        if (java.io.File.separatorChar == '\\') {
            ArgosConfig.BrowserInstallation drive = ArgosConfig.BrowserInstallation.fromEnvironment(
                    Map.of("BROWSER_DIR", "D:\\Argos Browser\\camoufox"), userHome, new java.util.Properties());
            assertEquals(Path.of("D:\\Argos Browser\\camoufox"), drive.root());
            assertEquals("D:\\", drive.root().getRoot().toString());
        }
    }

    private Path createArgosConfig(String content) throws IOException {
        Path config = userHome.resolve(".argos").resolve("argos.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, content);
        return config;
    }

    private static String propertyPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
