package com.grabx.app.grabx.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;

/** Installs the official directory distribution once, avoiding unpacking Python on every run. */
final class BundledYtDlpRuntime {
    static final String VERSION = "2026.08.19";
    static final String MAC_RESOURCE = "tools/yt-dlp/mac/runtime-" + VERSION + ".zip";
    static final String MAC_SHA256 = "07e54b0865303c864006925913bce2604f8ee8cc6f18699bac9c309f9328a6d8";

    private BundledYtDlpRuntime() {}

    static String platformResource(YtDlpManager.OS os, YtDlpManager.ARCH arch) {
        String cpu = switch (arch) { case X64 -> "x64"; case ARM64 -> "arm64"; case X86 -> "x86"; default -> null; };
        if (cpu == null) return null;
        if (os == YtDlpManager.OS.WINDOWS) return "windows/" + cpu + "/yt-dlp.exe";
        if (os == YtDlpManager.OS.LINUX && arch != YtDlpManager.ARCH.X86) return "linux/" + cpu + "/yt-dlp";
        return null;
    }

    static Path installStandalone(Path toolsDirectory, YtDlpManager.OS os, YtDlpManager.ARCH arch) throws IOException {
        String resource = platformResource(os, arch);
        if (resource == null) return null;
        var properties = new java.util.Properties();
        ClassLoader loader = BundledYtDlpRuntime.class.getClassLoader();
        try (var manifest = loader.getResourceAsStream("tools/yt-dlp/bundled.properties")) {
            if (manifest == null) throw new IOException("Missing yt-dlp checksums");
            properties.load(manifest);
        }
        String checksum = properties.getProperty(resource);
        if (!VERSION.equals(properties.getProperty("version")) || checksum == null) throw new IOException("Invalid yt-dlp manifest");
        String platform = resource.substring(0, resource.lastIndexOf('/')).replace('/', '-');
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        Path destination = toolsDirectory.resolve("yt-dlp-" + VERSION + "-" + platform);
        if (ready(destination, name, checksum)) return destination.resolve(name);
        Files.createDirectories(toolsDirectory);
        Path temporary = Files.createTempFile(toolsDirectory, ".yt-dlp-", ".tmp");
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
            try (InputStream source = loader.getResourceAsStream("tools/yt-dlp/" + resource)) {
                if (source == null) throw new IOException("Missing yt-dlp for " + platform);
                try (var input = new DigestInputStream(source, digest)) {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(checksum)) throw new IOException("yt-dlp checksum mismatch");
            if (os != YtDlpManager.OS.WINDOWS && !temporary.toFile().setExecutable(true, true)) throw new IOException("Cannot make yt-dlp executable");
            Files.createDirectories(destination);
            Files.move(temporary, destination.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(destination.resolve(".ready"), checksum);
            return destination.resolve(name);
        } finally { Files.deleteIfExists(temporary); }
    }

    static Path installMac(Path toolsDirectory) throws IOException {
        Path destination = toolsDirectory.resolve("yt-dlp-" + VERSION + "-macos");
        if (ready(destination, "yt-dlp_macos", MAC_SHA256)) return destination.resolve("yt-dlp_macos");
        try (InputStream archive = BundledYtDlpRuntime.class.getClassLoader().getResourceAsStream(MAC_RESOURCE)) {
            if (archive == null) return null;
            return install(archive, destination, "yt-dlp_macos", MAC_SHA256);
        }
    }

    static Path install(InputStream archive, Path destination, String executable, String sha256) throws IOException {
        if (ready(destination, executable, sha256)) return destination.resolve(executable);
        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(destination.getParent(), ".yt-dlp-install-");
        try {
            Path zip = staging.resolve("runtime.zip");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (Exception exception) { throw new IOException(exception); }
            try (var input = new DigestInputStream(archive, digest)) {
                Files.copy(input, zip);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(sha256)) throw new IOException("yt-dlp package checksum mismatch");
            long expandedBytes = 0;
            int entries = 0;
            try (var input = new ZipInputStream(Files.newInputStream(zip))) {
                java.util.zip.ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    if (++entries > 10_000) throw new IOException("Too many runtime files");
                    Path target = staging.resolve(entry.getName()).normalize();
                    if (!target.startsWith(staging) || target.equals(staging) || target.equals(zip)) {
                        throw new IOException("Invalid runtime archive path");
                    }
                    if (entry.isDirectory()) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        try (var output = Files.newOutputStream(target)) {
                            byte[] buffer = new byte[64 * 1024];
                            int length;
                            while ((length = input.read(buffer)) != -1) {
                                expandedBytes += length;
                                if (expandedBytes > 1_000_000_000L) throw new IOException("Runtime package is too large");
                                output.write(buffer, 0, length);
                            }
                        }
                    }
                }
            }
            Files.delete(zip);
            Path binary = staging.resolve(executable);
            if (!Files.isRegularFile(binary) || !binary.toFile().setExecutable(true, true)) {
                throw new IOException("Missing runtime executable");
            }
            Files.writeString(staging.resolve(".ready"), sha256);
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(staging, destination);
            } catch (IOException exception) {
                // Another app process may have completed the same installation.
                if (!ready(destination, executable, sha256)) throw exception;
            }
            return destination.resolve(executable);
        } finally {
            if (Files.exists(staging)) try (var files = Files.walk(staging)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static boolean ready(Path destination, String executable, String sha256) {
        try {
            return Files.isExecutable(destination.resolve(executable))
                    && Files.readString(destination.resolve(".ready")).equals(sha256);
        } catch (IOException exception) { return false; }
    }
}
