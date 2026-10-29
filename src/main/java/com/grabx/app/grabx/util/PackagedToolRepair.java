package com.grabx.app.grabx.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grabx.app.grabx.setup.ExtensionBundleService;
import java.io.IOException;
import java.nio.file.*;
import java.util.zip.ZipFile;

/** Offline repair from a verified, platform-specific archive shipped with native packages. */
public final class PackagedToolRepair {
    private PackagedToolRepair() {}
    static Path repairedDirectory() {
        try {
            Path content = InstalledLayout.contentDirectory();
            JsonNode manifest = new ObjectMapper().readTree(content.resolve("tool-repair.json").toFile());
            return dataRoot().resolve("runtimes/bundled-" + platform(manifest) + "-" + archiveHash(manifest));
        } catch (Exception e) { return null; }
    }
    private static Path dataRoot() {
        return ExtensionBundleService.dataRoot(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv());
    }
    private static String platform(JsonNode manifest) throws IOException {
        String target = manifest.path("target").asText();
        if (!target.equals(NodeRuntimeInstaller.platform(System.getProperty("os.name"), System.getProperty("os.arch"))))
            throw new IOException("This GrabX package does not match the operating system and processor. Install the matching package.");
        return target;
    }
    private static String archiveHash(JsonNode manifest) throws IOException {
        String hash = manifest.path("sha256").asText();
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid repair manifest. Reinstall GrabX.");
        return hash;
    }
    public static synchronized void prepare() throws IOException {
        Path content = InstalledLayout.contentDirectory();
        Path metadata = content.resolve("tool-repair.json");
        if (!Files.isRegularFile(metadata)) {
            if (Boolean.getBoolean("grabx.packaged")) throw new IOException("Tool repair files are missing. Reinstall GrabX.");
            return;
        }
        JsonNode manifest = new ObjectMapper().readTree(metadata.toFile());
        String target = platform(manifest), hash = archiveHash(manifest);
        Path destination = dataRoot().resolve("runtimes/bundled-" + target + "-" + hash);
        repair(content.resolve("tools"), content.resolve("tool-repair.zip"), destination, manifest,
                (name, path) -> probe(name, path));
    }
    @FunctionalInterface interface Probe { boolean usable(String name, Path file); }
    static void repair(Path original, Path archive, Path destination, JsonNode manifest, Probe probe) throws IOException {
        JsonNode files = manifest.path("files"), executables = manifest.path("executables");
        if (!files.isObject() || files.isEmpty() || !executables.isArray() || executables.isEmpty())
            throw new IOException("Incomplete repair manifest.");
        // Reuse a complete repair if present; otherwise prefer the original package.
        Path candidate = Files.isDirectory(destination) ? destination : original;
        if (valid(candidate, files)) { checkExecutables(candidate, executables, probe); return; }
        if (!Files.isRegularFile(archive) || !RuntimeFiles.sha256(archive).equals(archiveHash(manifest)))
            throw new IOException("The repair archive is missing or damaged. Reinstall GrabX.");
        Files.createDirectories(destination.getParent());
        Path staged = Files.createTempDirectory(destination.getParent(), ".tool-repair-");
        try {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                var entries = files.fields(); long total = 0;
                while (entries.hasNext()) {
                    var item = entries.next(); Path output = safePath(staged, item.getKey());
                    var entry = zip.getEntry(item.getKey());
                    if (entry == null || entry.isDirectory()) throw new IOException("Repair file is missing: " + item.getKey());
                    Files.createDirectories(output.getParent());
                    try (var in = zip.getInputStream(entry); var out = Files.newOutputStream(output)) {
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = in.read(buffer)) != -1) {
                            total += count; if (total > 1_000_000_000L) throw new IOException("Repair archive exceeds its limit.");
                            out.write(buffer, 0, count);
                        }
                    }
                }
            }
            if (!valid(staged, files)) throw new IOException("Repaired tool checksum mismatch.");
            for (var executable : executables) {
                Path file = safePath(staged, executable.asText());
                if (!file.toFile().setExecutable(true, true)) throw new IOException("Cannot enable tool execution.");
            }
            checkExecutables(staged, executables, probe);
            RuntimeFiles.replace(staged, destination);
        } finally { RuntimeFiles.deleteTree(staged); }
    }
    private static Path safePath(Path root, String name) throws IOException {
        Path path = root.resolve(name).normalize();
        if (name.contains("\\") || !path.startsWith(root) || path.equals(root)) throw new IOException("Invalid repair path.");
        return path;
    }
    private static boolean valid(Path root, JsonNode files) throws IOException {
        var entries = files.fields();
        while (entries.hasNext()) {
            var entry = entries.next(); Path file = safePath(root, entry.getKey());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !RuntimeFiles.sha256(file).equals(entry.getValue().asText())) return false;
        }
        return true;
    }
    private static void checkExecutables(Path root, JsonNode executables, Probe probe) throws IOException {
        for (var entry : executables) {
            String name = entry.asText();
            if (!probe.usable(name, safePath(root, name)))
                throw new IOException(name + " is intact but could not run. Check the system security report and OS compatibility; then retry.");
        }
    }
    private static boolean probe(String name, Path path) {
        String tool = name.replaceFirst("\\.exe$", "");
        String version = ToolExecutable.version(path, tool.startsWith("ff") ? "-version" : "--version");
        return tool.startsWith("ff") ? version != null && version.startsWith(tool + " version")
                : YtDlpOptions.supportedVersion(tool, version);
    }
}
