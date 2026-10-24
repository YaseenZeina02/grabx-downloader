package com.grabx.app.grabx.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipInputStream;

/** The installer carries the extension; exports live outside the app/DMG at a stable path. */
public final class ExtensionBundleService {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String MARKER = ".grabx-bundle.sha256";
    private final Path root;
    public ExtensionBundleService(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public static ExtensionBundleService forCurrentUser() {
        return new ExtensionBundleService(dataRoot(System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home")), System.getenv()).resolve("browser-extension"));
    }
    public static Path dataRoot(String os, Path home, Map<String, String> env) {
        if (os.toLowerCase().contains("mac")) return home.resolve("Library/Application Support/GrabX");
        if (os.toLowerCase().contains("win")) return Path.of(env.getOrDefault("LOCALAPPDATA", home.resolve("AppData/Local").toString())).resolve("GrabX");
        String data = env.get("XDG_DATA_HOME");
        return (data != null && Path.of(data).isAbsolute() ? Path.of(data) : home.resolve(".local/share")).resolve("GrabX");
    }
    public Path prepare() throws IOException {
        try (InputStream bundle = ExtensionBundleService.class.getResourceAsStream("/browser-extension/chromium.zip")) {
            if (bundle == null) throw new IOException("The bundled extension is missing. Reinstall GrabX.");
            return prepare(bundle);
        }
    }
    public synchronized Path prepare(InputStream bundle) throws IOException {
        byte[] archive = bundle.readNBytes(MAX_BYTES + 1);
        if (archive.length > MAX_BYTES) throw new IOException("Extension archive is too large.");
        Files.createDirectories(root);
        Path target = root.resolve("chromium"), staging = Files.createTempDirectory(root, ".prepare-");
        Path backup = null;
        boolean installed = false;
        try {
            Set<Path> names = new HashSet<>();
            long size = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    Path file = staging.resolve(name).normalize();
                    if (name.contains("\\") || !file.startsWith(staging) || file.equals(staging) || name.startsWith("/") || !names.add(file)
                            || names.size() > 256 || name.equals(MARKER)) throw new IOException("Invalid extension archive entry.");
                    if (entry.isDirectory()) { Files.createDirectories(file); continue; }
                    byte[] bytes = zip.readNBytes(MAX_BYTES + 1);
                    size += bytes.length;
                    if (size > MAX_BYTES) throw new IOException("Extension archive expands beyond its limit.");
                    Files.createDirectories(file.getParent()); Files.write(file, bytes);
                }
            }
            var manifest = new ObjectMapper().readTree(staging.resolve("manifest.json").toFile());
            if (manifest.path("manifest_version").asInt() != 3
                    || !"GrabX Download Assistant".equals(manifest.path("name").asText())
                    || !Files.isRegularFile(staging.resolve("service-worker.js"))
                    || !Files.isRegularFile(staging.resolve("popup.html"))) throw new IOException("Invalid bundled GrabX extension.");
            String digest;
            try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
            Files.writeString(staging.resolve(MARKER), digest);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target.resolve(MARKER), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("The extension destination contains unmanaged files. Choose another installation location.");
                if (sameFiles(staging, target)) return target;
                backup = Files.createTempDirectory(root, ".previous-"); Files.delete(backup);
                Files.move(target, backup);
            }
            try { Files.move(staging, target); installed = true; }
            catch (IOException e) {
                if (backup != null) { Files.move(backup, target); backup = null; }
                throw e;
            }
            return target;
        } finally {
            removeTree(staging);
            if (installed && backup != null) removeTree(backup);
        }
    }
    private static boolean sameFiles(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                Path other = target.resolve(source.relativize(path));
                if (!Files.isRegularFile(other, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(path, other) != -1) return false;
            }
        }
        try (var a = Files.walk(source); var b = Files.walk(target)) { return a.count() == b.count(); }
    }
    private static void removeTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
