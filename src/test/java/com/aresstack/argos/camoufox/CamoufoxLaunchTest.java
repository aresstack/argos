package com.aresstack.argos.camoufox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CamoufoxLaunchTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temporary;

    @Test
    void builtInProfileKeepsTheChosenFingerprintAndNoMachinePaths() throws Exception {
        ObjectNode profile = CamoufoxLaunch.defaultProfile();
        JsonNode config = profile.path("config");
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:156.0) Gecko/20100101 Firefox/156.0",
                config.path("navigator.userAgent").asText());
        assertEquals("Win32", config.path("navigator.platform").asText());
        assertEquals(24, config.path("navigator.hardwareConcurrency").asInt());
        assertEquals(1366, config.path("screen.width").asInt());
        assertTrue(config.path("fonts").size() > 100);
        assertTrue(config.path("webGl:renderer").asText().contains("GTX 980"));
        assertTrue(config.has("voices"));
        // Media devices stay one consistent machine from the launcher's catalogue, without a monitor model.
        assertEquals(1, config.path("mediaDevices:speakers").asInt());
        assertEquals(1, config.path("mediaDevices:speakerLabels").size());
        assertEquals(1, config.path("mediaDevices:speakerGroups").size());
        assertEquals("Speakers (Realtek(R) Audio)", config.path("mediaDevices:speakerLabels").get(0).asText());
        assertEquals(config.path("mediaDevices:micros").asInt(), config.path("mediaDevices:microphoneLabels").size());
        assertEquals(config.path("mediaDevices:webcams").asInt(), config.path("mediaDevices:webcamLabels").size());
        assertFalse(config.toString().contains("DELL"));
        assertTrue(config.has("audio:seed"));
        assertFalse(config.has("addons"), "add-on paths are resolved per installation");
        assertEquals("UBO", profile.path("bundledAddons").get(0).asText());
        assertEquals("en-US", profile.path("firefoxUserPrefs").path("intl.locale.requested").asText());
        String text = profile.toString();
        assertFalse(text.contains("C:\\\\Users"), "no developer paths");
        assertFalse(text.contains(".browser-event-hub"));
        // Argos owns the browser network settings; the default profile must not set them.
        profile.path("firefoxUserPrefs").fieldNames().forEachRemaining(key ->
                assertFalse(key.startsWith("network.proxy.") || key.startsWith("network.trr."), key));
    }

    @Test
    void windowsLaunchSplitsTheFingerprintInto2047CharacterVariables() throws Exception {
        Path browser = bundle("camoufox.exe");
        CamoufoxLaunch launch = CamoufoxLaunch.builtIn(CamoufoxLaunch.defaultProfile(),
                browser.resolve("camoufox.exe"), temporary.resolve("runtime"), "Windows 11");

        StringBuilder joined = new StringBuilder();
        int part = 1;
        for (; launch.environment().containsKey("CAMOU_CONFIG_" + part); part++) {
            String chunk = launch.environment().get("CAMOU_CONFIG_" + part);
            assertTrue(chunk.length() <= 2047);
            joined.append(chunk);
        }
        assertTrue(part > 3, "the fingerprint needs several variables on Windows");
        JsonNode config = MAPPER.readTree(joined.toString());
        assertEquals(browser.resolve("addons").resolve("UBO").toString(), config.path("addons").get(0).asText());
        assertEquals(1293, config.path("window.outerWidth").asInt());
        assertEquals(MAPPER.readTree(launch.environment().get("CAMOU_PREFS_1")),
                MAPPER.valueToTree(launch.prefs()));
        assertFalse(launch.environment().containsKey("FONTCONFIG_FILE"));
        assertTrue(launch.environment().keySet().stream().allMatch(key -> key.startsWith("CAMOU_")));
        assertTrue(launch.args().isEmpty());
    }

    @Test
    void linuxLaunchUsesTheBundledWindowsFonts() throws Exception {
        Path browser = bundle("camoufox");
        Files.createDirectories(browser.resolve("fontconfig/windows"));
        Files.writeString(browser.resolve("fontconfig/windows/fonts.conf"),
                "<fontconfig><dir prefix=\"cwd\">fonts</dir></fontconfig>");
        Files.createDirectories(browser.resolve("fonts/W"));
        Files.createDirectories(browser.resolve("fonts/LW"));
        Files.createDirectories(browser.resolve("fonts/L"));
        Files.writeString(browser.resolve("fonts/groups.json"), "{\"readBy\":{\"win\":[\"W\",\"LW\"],\"lin\":[\"L\"]}}");

        CamoufoxLaunch launch = CamoufoxLaunch.builtIn(CamoufoxLaunch.defaultProfile(),
                browser.resolve("camoufox"), temporary.resolve("runtime"), "Linux");

        assertFalse(launch.environment().containsKey("CAMOU_CONFIG_2"), "one variable suffices on Linux");
        Path fonts = Path.of(launch.environment().get("FONTCONFIG_FILE"));
        assertTrue(fonts.startsWith(temporary.resolve("runtime")));
        String conf = Files.readString(fonts);
        assertTrue(conf.contains("<dir>" + browser.resolve("fonts/W") + "</dir>"));
        assertTrue(conf.contains("<dir>" + browser.resolve("fonts/LW") + "</dir>"));
        assertFalse(conf.contains(browser.resolve("fonts/L") + "</dir>"));
    }

    @Test
    void refusesACamoufoxWithoutTheBundledAddOn() throws Exception {
        Path browser = temporary.resolve("own");
        Files.createDirectories(browser);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> CamoufoxLaunch.builtIn(
                CamoufoxLaunch.defaultProfile(), browser.resolve("camoufox.exe"), temporary, "Windows 11"));
        assertTrue(error.getMessage().contains("ARGOS_CAMOUFOX_LAUNCH_OPTIONS"));
    }

    @Test
    void explicitLaunchFileOverridesTheDefaultsAndMustExist() throws Exception {
        Path file = temporary.resolve("launch.json");
        Files.writeString(file, "{\"env\":{\"CAMOU_CONFIG_1\":\"{\\\"window.innerWidth\\\":800,\",\"CAMOU_CONFIG_2\":"
                + "\"\\\"window.innerHeight\\\":600}\"},\"firefoxUserPrefs\":{\"a.b\":1},\"args\":[\"-x\"]}");
        CamoufoxLaunch launch = CamoufoxLaunch.fromFile(file);
        assertEquals(800, launch.fingerprint().path("window.innerWidth").asInt());
        assertEquals(Map.of("a.b", 1), launch.prefs());
        assertEquals("-x", launch.args().get(0));

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> CamoufoxLaunch.fromFile(temporary.resolve("absent.json")));
        assertTrue(missing.getMessage().contains("does not exist"));
        Files.writeString(file, "{not json");
        assertThrows(IllegalArgumentException.class, () -> CamoufoxLaunch.fromFile(file));
        Files.writeString(file, "{\"env\":{\"CAMOU_CONFIG_1\":\"{}\",\"LD_PRELOAD\":\"x\"}}");
        assertThrows(IllegalArgumentException.class, () -> CamoufoxLaunch.fromFile(file));
    }

    @Test
    void chunksNeverSplitASurrogatePair() {
        String json = "ab\uD83D\uDE00cd";
        Map<String, String> parts = CamoufoxLaunch.chunks("P_", json, 3);
        assertEquals("ab", parts.get("P_1"));
        assertEquals(json, String.join("", parts.values()));
    }

    private Path bundle(String executable) throws Exception {
        Path browser = temporary.resolve("browser");
        Files.createDirectories(browser.resolve("addons").resolve("UBO"));
        Files.writeString(browser.resolve(executable), "");
        return browser;
    }
}
