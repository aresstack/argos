package com.aresstack.argos.camoufox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CamoufoxInstallerTest {
    private static final String TARGET = "lin.x86_64";
    private static final String FIX_A = "chrome/remote/content/shared/WindowManager.sys.mjs";
    private static final String FIX_B = "chrome/remote/content/webdriver-bidi/modules/windowglobal/browsingContext.sys.mjs";

    @TempDir Path temporary;

    @Test
    void installsVerifiesAndReusesWithoutManualPaths() throws Exception {
        Path mirror = temporary.resolve("mirror");
        CamoufoxDistribution distribution = publish(mirror, true, "fixed");
        CamoufoxInstaller installer = new CamoufoxInstaller(temporary.resolve("browser"), distribution, TARGET);

        Path executable = installer.installBlocking();

        assertEquals(temporary.resolve("browser").resolve("camoufox-1.0-argos.2-lin.x86_64").resolve("camoufox"),
                executable);
        assertTrue(Files.isRegularFile(executable));
        assertTrue(Files.isDirectory(executable.getParent().resolve("addons").resolve("UBO")));
        if (Files.getFileStore(executable).supportsFileAttributeView("posix")) {
            assertTrue(Files.isExecutable(executable), "manifest restores the executable bit");
        }
        assertEquals(CamoufoxInstaller.Phase.READY, installer.status().phase());
        assertNoPartials();

        // Reuse: the source may disappear, a new installer finds the verified install without downloading.
        Files.delete(mirror.resolve(distribution.archiveName(TARGET)));
        CamoufoxInstaller again = new CamoufoxInstaller(temporary.resolve("browser"), distribution, TARGET);
        assertEquals(executable, again.awaitExecutable(Duration.ofSeconds(5)));
    }

    @Test
    void installsIntoAConfiguredRootWithSpaces() throws Exception {
        CamoufoxDistribution distribution = publish(temporary.resolve("mirror"), true, "fixed");
        // BrowserInstallation hands BROWSER_DIR over as this root (ArgosConfigTest covers the resolution).
        Path root = temporary.resolve("Other Drive").resolve("Argos Browser");

        Path executable = new CamoufoxInstaller(root, distribution, TARGET).installBlocking();

        assertEquals(root.resolve("camoufox-1.0-argos.2-lin.x86_64").resolve("camoufox"), executable);
        assertTrue(Files.isRegularFile(executable));
    }

    @Test
    void rejectsAWrongChecksumAndInstallsNothing() throws Exception {
        CamoufoxDistribution published = publish(temporary.resolve("mirror"), true, "fixed");
        CamoufoxDistribution wrong = new CamoufoxDistribution(published.release(), published.revision(),
                published.baseUrl(), Map.of(TARGET, "0".repeat(64)), published.fixedEntries());
        CamoufoxInstaller installer = new CamoufoxInstaller(temporary.resolve("browser"), wrong, TARGET);

        RuntimeException error = assertThrows(RuntimeException.class, installer::installBlocking);

        assertTrue(error.getMessage().contains("SHA-256 mismatch"), error.getMessage());
        assertEquals(CamoufoxInstaller.Phase.FAILED, installer.status().phase());
        assertFalse(Files.exists(installer.installDirectory()));
        assertNoPartials();
    }

    @Test
    void rejectsAnArchiveWithoutTheBidiFixes() throws Exception {
        CamoufoxDistribution distribution = publish(temporary.resolve("mirror"), true, "released");
        CamoufoxInstaller installer = new CamoufoxInstaller(temporary.resolve("browser"), distribution, TARGET);

        RuntimeException error = assertThrows(RuntimeException.class, installer::installBlocking);

        assertTrue(error.getMessage().contains("WebDriver BiDi fix missing"), error.getMessage());
        assertFalse(Files.exists(installer.installDirectory()));
        assertNoPartials();
    }

    @Test
    void cleansUpAnInterruptedInstallationAndReplacesABrokenOne() throws Exception {
        CamoufoxDistribution distribution = publish(temporary.resolve("mirror"), true, "fixed");
        Path root = temporary.resolve("browser");
        CamoufoxInstaller installer = new CamoufoxInstaller(root, distribution, TARGET);
        // Leftovers of a killed download, and an install folder without the verification marker.
        Files.createDirectories(root.resolve(".partial-killed"));
        Files.writeString(root.resolve(".partial-killed").resolve("archive.zip"), "half");
        Files.createDirectories(installer.installDirectory());
        Files.writeString(installer.installDirectory().resolve("camoufox"), "incomplete");

        Path executable = installer.installBlocking();

        assertTrue(Files.isRegularFile(executable.getParent().resolve("omni.ja")));
        assertNoPartials();
    }

    @Test
    void keepsAnOlderVersionUntilTheNewOneIsVerified() throws Exception {
        Path root = temporary.resolve("browser");
        Path old = root.resolve("camoufox-0.9-argos.2-lin.x86_64");
        Files.createDirectories(old);
        Files.writeString(old.resolve("camoufox"), "old");
        CamoufoxDistribution broken = publish(temporary.resolve("mirror"), true, "released");

        assertThrows(RuntimeException.class, () -> new CamoufoxInstaller(root, broken, TARGET).installBlocking());

        assertEquals("old", Files.readString(old.resolve("camoufox")));
    }

    @Test
    void installsOnceForParallelRequests() throws Exception {
        CamoufoxDistribution distribution = publish(temporary.resolve("mirror"), true, "fixed");
        Path root = temporary.resolve("browser");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Path>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                // Separate installers, as separate processes would have; the file lock serialises them.
                CamoufoxInstaller installer = new CamoufoxInstaller(root, distribution, TARGET);
                results.add(pool.submit((Callable<Path>) installer::installBlocking));
            }
            Path first = results.get(0).get();
            for (Future<Path> result : results) assertEquals(first, result.get());
        } finally {
            pool.shutdownNow();
        }
        try (Stream<Path> entries = Files.list(root)) {
            assertEquals(List.of(".install.lock", "camoufox-1.0-argos.2-lin.x86_64"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void reportsProgressWhileTheInstallationStillRuns() throws Exception {
        CamoufoxDistribution distribution = publish(temporary.resolve("mirror"), true, "fixed");
        CamoufoxInstaller installer = new CamoufoxInstaller(temporary.resolve("browser"), distribution, TARGET);
        Path executable;
        try {
            executable = installer.awaitExecutable(Duration.ZERO);
        } catch (CamoufoxInstaller.NotReadyException pending) {
            assertTrue(pending.getMessage().startsWith("Argos is") || pending.getMessage().startsWith("Camoufox"),
                    pending.getMessage());
            executable = installer.awaitExecutable(Duration.ofSeconds(30));
        }
        assertTrue(Files.isRegularFile(executable));
    }

    @Test
    void explainsAMissingDownloadSource() throws Exception {
        CamoufoxDistribution distribution = publish(temporary.resolve("mirror"), true, "fixed");
        Files.delete(temporary.resolve("mirror").resolve(distribution.archiveName(TARGET)));
        CamoufoxInstaller installer = new CamoufoxInstaller(temporary.resolve("browser"), distribution, TARGET);

        RuntimeException error = assertThrows(RuntimeException.class, () -> installer.awaitExecutable(Duration.ofSeconds(30)));

        assertTrue(error.getMessage().contains("not found"), error.getMessage());
        assertTrue(installer.status().describe().startsWith("Camoufox installation failed"));
    }

    @Test
    void refusesUnsupportedPlatformsAndUnpublishedArchives() {
        RuntimeException mac = assertThrows(CamoufoxDistribution.UnsupportedPlatformException.class,
                () -> CamoufoxDistribution.target("Mac OS X", "aarch64"));
        assertTrue(mac.getMessage().contains("only on Windows x86_64 and Linux x86_64"), mac.getMessage());
        assertThrows(CamoufoxDistribution.UnsupportedPlatformException.class,
                () -> CamoufoxDistribution.target("Linux", "aarch64"));
        assertEquals("win.x86_64", CamoufoxDistribution.target("Windows 11", "amd64"));
        assertEquals("lin.x86_64", CamoufoxDistribution.target("Linux", "amd64"));

        CamoufoxDistribution pending = new CamoufoxDistribution("1.0", "argos.2",
                temporary.toUri(), Map.of(TARGET, "PENDING"), Map.of());
        assertThrows(IllegalStateException.class, () -> pending.sha256(TARGET));
        assertThrows(CamoufoxDistribution.UnsupportedPlatformException.class, () -> pending.sha256("mac.arm64"));
    }

    @Test
    void builtInDistributionPinsTheTestedRelease() {
        CamoufoxDistribution builtIn = CamoufoxDistribution.builtIn();
        assertEquals("156.0.1-beta.36", builtIn.release());
        assertEquals(Map.of(FIX_A, "047cca48c67255dc76523faa054487a641d1103e188c62ed2b06cd7756b0d46b",
                FIX_B, "4202f76e04e88059c1e6b9797228f4a04f9ed1d210984d5818f00ccc790df460"), builtIn.fixedEntries());
        assertEquals("https", builtIn.archiveUrl("win.x86_64").getScheme());
        assertTrue(builtIn.archiveUrl("win.x86_64").toString()
                .endsWith("/camoufox-156.0.1-beta.36-argos.2-win.x86_64.zip"));
        assertEquals(Map.of("win.x86_64", 64, "lin.x86_64", 64), Map.of(
                "win.x86_64", builtIn.sha256("win.x86_64").length(), "lin.x86_64", builtIn.sha256("lin.x86_64").length()));
    }

    private void assertNoPartials() throws IOException {
        Path root = temporary.resolve("browser");
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> entries = Files.list(root)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith(".partial-")));
        }
    }

    /** A miniature distribution archive in {@code mirror}; {@code omniState} "fixed" or "released". */
    private static CamoufoxDistribution publish(Path mirror, boolean withManifest, String omniState) throws Exception {
        Files.createDirectories(mirror);
        byte[] fixA = "window fix".getBytes(StandardCharsets.UTF_8);
        byte[] fixB = "visibility fix".getBytes(StandardCharsets.UTF_8);
        CamoufoxDistribution draft = new CamoufoxDistribution("1.0", "argos.2", mirror.toUri(), Map.of(),
                Map.of(FIX_A, sha(fixA), FIX_B, sha(fixB)));
        java.io.ByteArrayOutputStream omni = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(omni)) {
            put(zip, FIX_A, "fixed".equals(omniState) ? fixA : "released".getBytes(StandardCharsets.UTF_8));
            put(zip, FIX_B, fixB);
        }
        Path archive = mirror.resolve(draft.archiveName(TARGET));
        try (OutputStream file = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(file)) {
            put(zip, "camoufox", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
            put(zip, "omni.ja", omni.toByteArray());
            put(zip, "addons/UBO/manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
            if (withManifest) {
                put(zip, CamoufoxDistribution.MANIFEST, ("{\"release\":\"1.0\",\"revision\":\"argos.2\",\"target\":\""
                        + TARGET + "\",\"executables\":[\"camoufox\"]}").getBytes(StandardCharsets.UTF_8));
            }
        }
        return new CamoufoxDistribution("1.0", "argos.2", mirror.toUri(),
                Map.of(TARGET, sha(Files.readAllBytes(archive))), draft.fixedEntries());
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
