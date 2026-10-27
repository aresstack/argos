package com.aresstack.argos.camoufox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs the pinned Argos Camoufox distribution under {@code <root>/<camoufox-release-revision>-<target>/}
 * ({@code root} is {@code ~/.argos/browser} by default) and reuses it afterwards.
 *
 * <p>Download and unpacking happen in {@code <root>/.partial-<random>/}; the folder becomes the install only after
 * the archive's SHA-256, the expected layout and both WebDriver BiDi fixes were verified, by one atomic rename. An
 * older install stays untouched. Leftover partial folders are removed by the next attempt. One installation runs at
 * a time: in this JVM through one shared future, across processes through {@code <root>/.install.lock}.
 *
 * <p>Nothing here logs fingerprint values, environment variables or proxy credentials.
 */
public final class CamoufoxInstaller {
    public enum Phase { NOT_INSTALLED, DOWNLOADING, VERIFYING, READY, FAILED }

    /** What the installation is doing, for tool results while the browser is not ready yet. */
    public record Status(Phase phase, long downloaded, long size, String detail) {
        public String describe() {
            return switch (phase) {
                case NOT_INSTALLED -> "Camoufox is not installed yet.";
                case DOWNLOADING -> "Argos is downloading its Camoufox browser (" + (downloaded >> 20) + " of "
                        + (size > 0 ? (size >> 20) + " MiB" : "? MiB") + "); retry the tool in a minute.";
                case VERIFYING -> "Argos is verifying and unpacking its Camoufox browser; retry the tool shortly.";
                case READY -> "Camoufox is installed.";
                case FAILED -> "Camoufox installation failed: " + detail;
            };
        }
    }

    /** Thrown by {@link #awaitExecutable} while the installation still runs. */
    public static final class NotReadyException extends IllegalStateException {
        public NotReadyException(String message) {
            super(message);
        }
    }

    private static final String MARKER = ".argos-verified";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static CamoufoxInstaller shared;
    /** File locks belong to the JVM, so installers of one root in this process queue here first. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, Object> ROOT_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Path root;
    private final CamoufoxDistribution distribution;
    private final String target;
    private final AtomicLong downloaded = new AtomicLong();
    private volatile long size = -1;
    private volatile Phase phase = Phase.NOT_INSTALLED;
    private volatile String failure;
    private CompletableFuture<Path> running;

    public CamoufoxInstaller(Path root, CamoufoxDistribution distribution, String target) {
        this.root = root.toAbsolutePath().normalize();
        this.distribution = distribution;
        this.target = target;
    }

    /** The one installer of this process: {@code ~/.argos/browser}, or {@code ARGOS_CAMOUFOX_DISTRIBUTION_URL}. */
    public static synchronized CamoufoxInstaller shared(Path root, URI mirror) {
        if (shared == null || !shared.root.equals(root.toAbsolutePath().normalize())) {
            CamoufoxDistribution distribution = CamoufoxDistribution.builtIn();
            shared = new CamoufoxInstaller(root, mirror == null ? distribution : distribution.from(mirror),
                    CamoufoxDistribution.currentTarget());
        }
        return shared;
    }

    public Path root() {
        return root;
    }

    public Path installDirectory() {
        return root.resolve(distribution.name() + "-" + target);
    }

    public Path executable() {
        return installDirectory().resolve(CamoufoxDistribution.executableName(target));
    }

    public Status status() {
        return new Status(phase, downloaded.get(), size, failure);
    }

    /**
     * The verified executable; starts the installation if needed and waits at most {@code wait} for it. While it
     * still runs this throws {@link NotReadyException} with the progress; the installation continues in the
     * background. A failed installation is reported once and retried by the next call.
     */
    public Path awaitExecutable(Duration wait) {
        CompletableFuture<Path> installation;
        synchronized (this) {
            if (running == null || running.isCompletedExceptionally()) {
                if (phase != Phase.READY && verified(installDirectory())) phase = Phase.READY;
                if (phase == Phase.READY) return executable();
                running = CompletableFuture.supplyAsync(this::installBlocking, runnable -> {
                    Thread thread = new Thread(runnable, "argos-camoufox-install");
                    thread.setDaemon(true);
                    thread.start();
                });
            }
            installation = running;
        }
        try {
            return installation.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
            throw new NotReadyException(status().describe());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NotReadyException(status().describe());
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
    }

    /** Installs (or finds) the distribution and returns its executable; blocks for the whole download. */
    public Path installBlocking() {
        synchronized (ROOT_LOCKS.computeIfAbsent(root, any -> new Object())) {
            return installLocked();
        }
    }

    private Path installLocked() {
        try {
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(root.resolve(".install.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Path install = installDirectory();
                if (verified(install)) {
                    phase = Phase.READY;
                    return executable();
                }
                removePartials();
                if (Files.exists(install)) {
                    // Not produced by a completed installation of this Argos (or changed since): set aside, replace.
                    Path broken = root.resolve(".partial-" + UUID.randomUUID());
                    Files.move(install, broken);
                    deleteTree(broken);
                }
                Path partial = root.resolve(".partial-" + UUID.randomUUID());
                Files.createDirectories(partial);
                try {
                    Path archive = partial.resolve(distribution.archiveName(target));
                    download(archive);
                    phase = Phase.VERIFYING;
                    Path tree = partial.resolve("tree");
                    unpack(archive, tree);
                    Files.delete(archive);
                    verifyTree(tree);
                    Files.writeString(tree.resolve(MARKER), distribution.sha256(target) + "\n");
                    try {
                        Files.move(tree, install, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException sameVolumeOnly) {
                        throw new IOException("cannot rename " + tree + " to " + install + " atomically",
                                sameVolumeOnly);
                    }
                } finally {
                    deleteTree(partial);
                }
                if (!verified(install)) throw new IOException("installed Camoufox failed verification: " + install);
                phase = Phase.READY;
                failure = null;
                System.err.println("Camoufox " + distribution.name() + " (" + target + ") installed in " + install);
                return executable();
            }
        } catch (IOException | RuntimeException error) {
            phase = Phase.FAILED;
            failure = error.getMessage();
            if (error instanceof IOException io) throw new UncheckedIOException(io.getMessage(), io);
            throw (RuntimeException) error;
        }
    }

    private void download(Path archive) throws IOException {
        String expected = distribution.sha256(target);
        URI url = distribution.archiveUrl(target);
        phase = Phase.DOWNLOADING;
        downloaded.set(0);
        size = -1;
        System.err.println("Downloading Camoufox " + distribution.name() + " (" + target + ") from " + safe(url));
        MessageDigest digest = sha256();
        try (InputStream in = open(url); OutputStream out = Files.newOutputStream(archive)) {
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                downloaded.addAndGet(read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expected)) {
            throw new IOException("SHA-256 mismatch for " + distribution.archiveName(target) + ": expected "
                    + expected + ", got " + actual + " (incomplete or altered download; nothing was installed)");
        }
    }

    private InputStream open(URI url) throws IOException {
        if ("file".equals(url.getScheme())) {
            Path file = Path.of(url);
            if (!Files.isRegularFile(file)) throw new IOException("Camoufox archive not found: " + file);
            size = Files.size(file);
            return Files.newInputStream(file);
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("download source answered HTTP " + response.statusCode() + " for "
                        + safe(url));
            }
            size = response.headers().firstValueAsLong("content-length").orElse(-1);
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", interrupted);
        }
    }

    private static void unpack(Path archive, Path tree) throws IOException {
        Files.createDirectories(tree);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : zip.stream().map(ZipEntry.class::cast).toList()) {
                Path destination = tree.resolve(entry.getName()).normalize();
                if (!destination.startsWith(tree)) throw new IOException("archive entry escapes: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination);
                }
            }
        }
    }

    /** Layout, POSIX executable bits from the manifest, the executable and both BiDi fixes. */
    private void verifyTree(Path tree) throws IOException {
        Path manifestFile = tree.resolve(CamoufoxDistribution.MANIFEST);
        if (!Files.isRegularFile(manifestFile)) throw new IOException("archive lacks " + CamoufoxDistribution.MANIFEST);
        JsonNode manifest = MAPPER.readTree(manifestFile.toFile());
        if (!distribution.release().equals(manifest.path("release").asText())
                || !distribution.revision().equals(manifest.path("revision").asText())
                || !target.equals(manifest.path("target").asText())) {
            throw new IOException("archive is not " + distribution.name() + " for " + target);
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            for (JsonNode file : manifest.path("executables")) {
                Path path = tree.resolve(file.asText()).normalize();
                if (!path.startsWith(tree) || !Files.isRegularFile(path)) {
                    throw new IOException("manifest names a missing executable: " + file.asText());
                }
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
        checkFixes(tree);
    }

    private boolean verified(Path install) {
        try {
            if (!Files.isRegularFile(install.resolve(MARKER))
                    || !Files.readString(install.resolve(MARKER)).trim().equals(distribution.archiveSha256().get(target))
                    || !Files.isRegularFile(install.resolve(CamoufoxDistribution.executableName(target)))) {
                return false;
            }
            checkFixes(install);
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    /** Both omni.ja entries must have the SHA-256 of the fixed files (hotpatch_omni.py --check equivalent). */
    void checkFixes(Path install) throws IOException {
        Path omni = install.resolve("omni.ja");
        if (!Files.isRegularFile(omni)) throw new IOException("no omni.ja in " + install);
        try (ZipFile zip = new ZipFile(omni.toFile())) {
            for (Map.Entry<String, String> fix : distribution.fixedEntries().entrySet()) {
                ZipEntry entry = zip.getEntry(fix.getKey());
                if (entry == null) throw new IOException("omni.ja lacks " + fix.getKey());
                MessageDigest digest = sha256();
                try (InputStream in = zip.getInputStream(entry)) {
                    digest.update(in.readAllBytes());
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(fix.getValue())) {
                    throw new IOException("WebDriver BiDi fix missing in omni.ja: " + fix.getKey());
                }
            }
        }
    }

    private void removePartials() throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.filter(p -> p.getFileName().toString().startsWith(".partial-")).toList()) {
                deleteTree(entry);
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The URL without user info or query, which could carry credentials of a mirror. */
    private static String safe(URI url) {
        return url.getScheme() + "://" + (url.getHost() == null ? "" : url.getHost()) + url.getPath();
    }
}
