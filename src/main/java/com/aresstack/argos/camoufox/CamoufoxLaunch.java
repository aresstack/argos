package com.aresstack.argos.camoufox;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Camoufox is started with: fingerprint environment ({@code CAMOU_CONFIG_1..n}, {@code CAMOU_PREFS_1..n},
 * {@code FONTCONFIG_FILE}), Firefox prefs and arguments. Either from a launch.json the operator names explicitly
 * ({@code ARGOS_CAMOUFOX_LAUNCH_OPTIONS}), or from Argos' built-in profile {@code camoufox/default-launch.json}.
 *
 * @param environment only the Camoufox keys; the caller adds them to the inherited process environment
 * @param fingerprint the joined {@code CAMOU_CONFIG} object (for the window size real input needs)
 * @param proxy the launch.json {@code proxy} node, missing for the built-in profile
 */
public record CamoufoxLaunch(Map<String, String> environment, JsonNode fingerprint, Map<String, Object> prefs,
                             List<String> args, JsonNode proxy) {

    static final String DEFAULT_PROFILE = "/camoufox/default-launch.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An explicitly configured launch.json; a missing or invalid file is an error, never a silent fallback. */
    public static CamoufoxLaunch fromFile(Path file) throws IOException {
        JsonNode launch;
        try {
            launch = MAPPER.readTree(Files.readString(file));
        } catch (NoSuchFileException missing) {
            throw new IllegalStateException("ARGOS_CAMOUFOX_LAUNCH_OPTIONS / camoufox.launchOptions names " + file
                    + ", which does not exist. Remove the setting to use Argos' built-in Camoufox profile.");
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Camoufox launch options " + file + " are not valid JSON: "
                    + invalid.getClass().getSimpleName());
        }
        if (launch == null || !launch.isObject()) {
            throw new IllegalArgumentException("Camoufox launch options " + file + " must be a JSON object.");
        }
        JsonNode variables = launch.path("env");
        if (!variables.isObject() || !variables.has("CAMOU_CONFIG_1")) {
            throw new IllegalArgumentException("Official Camoufox fingerprint file requires env.CAMOU_CONFIG_1");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        variables.fields().forEachRemaining(entry -> {
            if (!entry.getKey().startsWith("CAMOU_") && !entry.getKey().startsWith("FONTCONFIG")) {
                throw new IllegalArgumentException("Unexpected fingerprint environment key: " + entry.getKey());
            }
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("Invalid fingerprint environment value: " + entry.getKey());
            }
            environment.put(entry.getKey(), entry.getValue().asText());
        });
        StringBuilder json = new StringBuilder();
        for (int part = 1; environment.containsKey("CAMOU_CONFIG_" + part); part++) {
            json.append(environment.get("CAMOU_CONFIG_" + part));
        }
        JsonNode fingerprint;
        try {
            fingerprint = MAPPER.readTree(json.toString());
        } catch (IOException unreadable) {
            fingerprint = MAPPER.createObjectNode();
        }
        JsonNode preferences = launch.has("firefoxUserPrefs") ? launch.get("firefoxUserPrefs")
                : launch.path("firefox_user_prefs");
        return new CamoufoxLaunch(environment, fingerprint, prefs(preferences), args(launch.path("args")),
                launch.path("proxy"));
    }

    /**
     * Argos' built-in profile for the Camoufox at {@code executable}. Add-ons it names are taken from
     * {@code <executable dir>/addons/<name>} (shipped in the Argos distribution). On Linux the fonts of the
     * fingerprint's OS come from the bundle's fontconfig; the generated fonts.conf is written to {@code runtimeDir}.
     */
    public static CamoufoxLaunch builtIn(Path executable, Path runtimeDir) throws IOException {
        return builtIn(defaultProfile(), executable, runtimeDir, System.getProperty("os.name"));
    }

    static ObjectNode defaultProfile() throws IOException {
        try (InputStream in = CamoufoxLaunch.class.getResourceAsStream(DEFAULT_PROFILE)) {
            if (in == null) throw new IllegalStateException(DEFAULT_PROFILE + " missing from argos.jar");
            return (ObjectNode) MAPPER.readTree(in);
        }
    }

    static CamoufoxLaunch builtIn(ObjectNode profile, Path executable, Path runtimeDir, String hostOs)
            throws IOException {
        Path browserDir = executable.toAbsolutePath().getParent();
        ObjectNode config = profile.path("config").deepCopy();
        ArrayNode addons = config.putArray("addons");
        for (JsonNode name : profile.path("bundledAddons")) {
            Path addon = browserDir.resolve("addons").resolve(name.asText());
            if (!Files.isDirectory(addon)) {
                throw new IllegalStateException("Argos' built-in Camoufox profile loads the add-on " + name.asText()
                        + ", which this Camoufox does not ship in " + addon.getParent() + ". Use the Argos Camoufox "
                        + "distribution, or name your own launch.json in ARGOS_CAMOUFOX_LAUNCH_OPTIONS.");
            }
            addons.add(addon.toString());
        }
        boolean windows = hostOs.toLowerCase(Locale.ROOT).startsWith("windows");
        Map<String, Object> prefs = prefs(profile.path("firefoxUserPrefs"));
        Map<String, String> environment = new LinkedHashMap<>();
        // Same chunking as the camoufox launcher (utils.get_env_vars): Windows caps one variable at 2047 chars.
        int chunk = windows ? 2047 : 32767;
        environment.putAll(chunks("CAMOU_CONFIG_", MAPPER.writeValueAsString(config), chunk));
        // Prefs Gecko reads during startup only apply through settings/camoufox.cfg (utils.get_pref_env_vars),
        // ASCII-escaped because autoconfig reads the environment through the ANSI code page on Windows.
        if (!prefs.isEmpty()) {
            String json = MAPPER.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature()).writeValueAsString(prefs);
            environment.putAll(chunks("CAMOU_PREFS_", json, chunk));
        }
        if (!windows) {
            environment.put("FONTCONFIG_FILE", fontconfig(browserDir, fontOs(config), runtimeDir).toString());
        }
        return new CamoufoxLaunch(environment, config, prefs, args(profile.path("args")), MAPPER.missingNode());
    }

    /** {@code CAMOU_CONFIG_1..n}: the JSON split at {@code size} characters, never inside a surrogate pair. */
    static Map<String, String> chunks(String prefix, String json, int size) {
        Map<String, String> parts = new LinkedHashMap<>();
        int part = 1;
        for (int start = 0; start < json.length(); part++) {
            int end = Math.min(json.length(), start + size);
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))) end--;
            parts.put(prefix + part, json.substring(start, end));
            start = end;
        }
        return parts;
    }

    private static String fontOs(JsonNode config) {
        String platform = config.path("navigator.platform").asText("").toLowerCase(Locale.ROOT);
        if (platform.startsWith("win")) return "windows";
        if (platform.startsWith("mac")) return "macos";
        return "linux";
    }

    /**
     * The camoufox launcher's {@code _generate_fontconfig}: the bundle's {@code fontconfig/<os>/fonts.conf} with
     * its relative font directory replaced by the absolute font groups that OS reads ({@code fonts/groups.json}).
     */
    static Path fontconfig(Path browserDir, String os, Path runtimeDir) throws IOException {
        Path source = browserDir.resolve("fontconfig").resolve(os).resolve("fonts.conf");
        if (!Files.isRegularFile(source)) source = browserDir.resolve("fontconfigs").resolve(os).resolve("fonts.conf");
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("fonts.conf for " + os + " missing in the Camoufox bundle " + browserDir);
        }
        Path fonts = browserDir.resolve("fonts");
        List<String> dirs = new ArrayList<>();
        Path groups = fonts.resolve("groups.json");
        String key = switch (os) { case "windows" -> "win"; case "macos" -> "mac"; default -> "lin"; };
        if (Files.isRegularFile(groups)) {
            for (JsonNode group : MAPPER.readTree(groups.toFile()).path("readBy").path(key)) {
                if (Files.isDirectory(fonts.resolve(group.asText()))) dirs.add(fonts.resolve(group.asText()).toString());
            }
        }
        if (dirs.isEmpty()) dirs.add(Files.isDirectory(fonts.resolve(os)) ? fonts.resolve(os).toString() : fonts.toString());
        StringBuilder replacement = new StringBuilder();
        for (String dir : dirs) {
            if (!replacement.isEmpty()) replacement.append("\n\t");
            replacement.append("<dir>").append(dir).append("</dir>");
        }
        String conf = Files.readString(source).replace("<dir prefix=\"cwd\">fonts</dir>", replacement);
        byte[] bytes = conf.getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Path target = runtimeDir.resolve("fontconfig").resolve("fonts-" + hash + ".conf");
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "fonts-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        return target;
    }

    private static Map<String, Object> prefs(JsonNode preferences) {
        Map<String, Object> prefs = new LinkedHashMap<>();
        preferences.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isBoolean()) prefs.put(entry.getKey(), value.asBoolean());
            else if (value.isIntegralNumber()) prefs.put(entry.getKey(), value.asInt());
            else if (value.isTextual()) prefs.put(entry.getKey(), value.asText());
            else throw new IllegalArgumentException("Unsupported Firefox pref: " + entry.getKey());
        });
        return prefs;
    }

    private static List<String> args(JsonNode values) {
        List<String> args = new ArrayList<>();
        values.forEach(value -> args.add(value.asText()));
        return args;
    }
}
