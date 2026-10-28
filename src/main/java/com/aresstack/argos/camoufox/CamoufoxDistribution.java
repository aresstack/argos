package com.aresstack.argos.camoufox;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The pinned, patched Camoufox build Argos installs by itself: release, Argos revision, download location and the
 * SHA-256 of each platform archive and of the two fixed omni.ja entries. Read from
 * {@code camoufox/distribution.properties}, versioned independently of Argos.
 */
public record CamoufoxDistribution(String release, String revision, URI baseUrl,
                                   Map<String, String> archiveSha256, Map<String, String> fixedEntries) {

    public static final String MANIFEST = "argos-distribution.json";

    public CamoufoxDistribution {
        if (!baseUrl.toString().endsWith("/")) baseUrl = URI.create(baseUrl + "/");
        archiveSha256 = Map.copyOf(archiveSha256);
        fixedEntries = Map.copyOf(fixedEntries);
    }

    public static CamoufoxDistribution builtIn() {
        try (InputStream in = CamoufoxDistribution.class.getResourceAsStream("/camoufox/distribution.properties")) {
            if (in == null) throw new IllegalStateException("camoufox/distribution.properties missing from argos.jar");
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> archives = new LinkedHashMap<>();
            Map<String, String> fixes = new LinkedHashMap<>();
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("sha256.")) archives.put(key.substring(7), properties.getProperty(key).trim());
                if (key.startsWith("fix.")) fixes.put(key.substring(4), properties.getProperty(key).trim());
            }
            return new CamoufoxDistribution(properties.getProperty("release").trim(),
                    properties.getProperty("revision").trim(), URI.create(properties.getProperty("baseUrl").trim()),
                    archives, fixes);
        } catch (IOException unreadable) {
            throw new IllegalStateException("camoufox/distribution.properties unreadable", unreadable);
        }
    }

    /** The same build from another location (a mirror); the pinned checksums stay. */
    public CamoufoxDistribution from(URI mirror) {
        return new CamoufoxDistribution(release, revision, mirror, archiveSha256, fixedEntries);
    }

    /** {@code camoufox-<release>-<revision>}, also the install folder name. */
    public String name() {
        return "camoufox-" + release + "-" + revision;
    }

    public String archiveName(String target) {
        return name() + "-" + target + ".zip";
    }

    public URI archiveUrl(String target) {
        return baseUrl.resolve(archiveName(target));
    }

    /** The pinned archive checksum for {@code target}; fails for platforms without a published archive. */
    public String sha256(String target) {
        String sha = archiveSha256.get(target);
        if (sha == null) {
            throw new UnsupportedPlatformException("No Argos Camoufox distribution for " + target
                    + "; supported: " + archiveSha256.keySet()
                    + ".");
        }
        if (!sha.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("The Argos Camoufox distribution " + name() + " for " + target
                    + " is not published yet (no pinned SHA-256).");
        }
        return sha;
    }

    /** {@code win.x86_64} or {@code lin.x86_64}, the platforms Argos publishes; anything else is unsupported. */
    public static String currentTarget() {
        return target(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static String target(String osName, String arch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String system = os.startsWith("windows") ? "win" : os.startsWith("linux") ? "lin" : null;
        String machine = arch.toLowerCase(Locale.ROOT);
        boolean x64 = machine.equals("amd64") || machine.equals("x86_64");
        if (system == null || !x64) {
            throw new UnsupportedPlatformException("Argos installs Camoufox only on Windows x86_64 and Linux x86_64, "
                    + "not " + osName + " " + arch + ".");
        }
        return system + ".x86_64";
    }

    public static String executableName(String target) {
        return target.startsWith("win") ? "camoufox.exe" : "camoufox";
    }

    public static final class UnsupportedPlatformException extends IllegalStateException {
        public UnsupportedPlatformException(String message) {
            super(message);
        }
    }
}
