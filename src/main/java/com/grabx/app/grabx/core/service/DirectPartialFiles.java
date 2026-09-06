package com.grabx.app.grabx.core.service;

import java.io.IOException;
import java.nio.file.*;

/** Exact per-output ownership for direct-download working files, including legacy names. */
public final class DirectPartialFiles {
    private DirectPartialFiles() { }

    public static Path part(Path output, int segment) {
        String suffix = ".grabx.part" + (segment < 0 ? "" : "." + segment);
        Path legacy = output.resolveSibling(output.getFileName() + suffix);
        return Files.exists(legacy) ? legacy : output.resolveSibling("." + output.getFileName() + suffix);
    }

    public static boolean hasParts(Path output) {
        if (output == null) return false;
        try (var entries = Files.newDirectoryStream(output.toAbsolutePath().getParent())) {
            for (Path entry : entries) if (owns(output, entry)) return true;
        } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        return false;
    }

    static boolean owns(Path output, Path entry) {
        String name = entry.getFileName().toString();
        String base = output.getFileName().toString() + ".grabx.part";
        return matches(name, base) || matches(name, "." + base);
    }

    private static boolean matches(String name, String base) {
        return name.equals(base) || (name.startsWith(base + ".")
                && name.substring(base.length() + 1).matches("[0-9]+"));
    }

    public static void hideLegacyParts(Path output) throws IOException {
        try (var entries = Files.newDirectoryStream(output.toAbsolutePath().getParent())) {
            String base = output.getFileName() + ".grabx.part";
            for (Path entry : entries) {
                if (matches(entry.getFileName().toString(), base) && Files.isRegularFile(entry)) {
                    Path hidden = entry.resolveSibling("." + entry.getFileName());
                    if (Files.exists(hidden)) throw new IOException("Conflicting saved download parts");
                    Files.move(entry, hidden);
                }
            }
        }
    }

    public static boolean expired(Path output, long cutoff) throws IOException {
        if (output == null || !Files.isDirectory(output.toAbsolutePath().getParent())) return false;
        boolean found = false;
        try (var entries = Files.newDirectoryStream(output.toAbsolutePath().getParent())) {
            for (Path entry : entries) {
                if (!owns(output, entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                found = true;
                // Keep the whole task while any segment has received data recently.
                if (Files.getLastModifiedTime(entry).toMillis() >= cutoff) return false;
            }
        }
        return found;
    }

    public static void cleanup(Path output) throws IOException {
        if (output == null || !Files.isDirectory(output.toAbsolutePath().getParent())) return;
        try (var entries = Files.newDirectoryStream(output.toAbsolutePath().getParent())) {
            for (Path entry : entries) {
                if (owns(output, entry) && !Files.isDirectory(entry)) Files.deleteIfExists(entry);
            }
        }
    }
}
