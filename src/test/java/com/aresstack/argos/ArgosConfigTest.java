package com.aresstack.argos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
                + "camoufox.launchOptions=" + propertyPath(launchOptions) + System.lineSeparator());

        ArgosConfig config = ArgosConfig.fromEnvironment(Map.of(), userHome, argosConfig);

        assertEquals(executable.toAbsolutePath(), config.executable());
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
                + "camoufox.launchOptions=" + propertyPath(configuredLaunchOptions) + System.lineSeparator());

        ArgosConfig config = ArgosConfig.fromEnvironment(Map.of(
                "ARGOS_CAMOUFOX_EXECUTABLE", environmentExecutable.toString(),
                "ARGOS_CAMOUFOX_LAUNCH_OPTIONS", environmentLaunchOptions.toString(),
                "SERVER_PORT", "8086"
        ), userHome, argosConfig);

        assertEquals(environmentExecutable.toAbsolutePath(), config.executable());
        assertEquals(environmentLaunchOptions.toAbsolutePath(), config.launchOptions());
        assertEquals(8086, config.port());
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
