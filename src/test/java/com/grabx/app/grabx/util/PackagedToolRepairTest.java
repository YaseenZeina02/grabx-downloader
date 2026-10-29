package com.grabx.app.grabx.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PackagedToolRepairTest {
    @TempDir Path root;
    @Test void repairsMissingAndCorruptCopiesOfflineAndReusesValidFiles() throws Exception {
        Path archive = root.resolve("repair.zip"), original = root.resolve("original"), target = root.resolve("private");
        Files.createDirectories(original); Files.writeString(original.resolve("node.exe"), "verified node");
        var manifest = new ObjectMapper().createObjectNode();
        manifest.putObject("files").put("node.exe", RuntimeFiles.sha256(original.resolve("node.exe")));
        manifest.putArray("executables").add("node.exe");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("node.exe")); zip.write(Files.readAllBytes(original.resolve("node.exe"))); zip.closeEntry();
        }
        manifest.put("sha256", RuntimeFiles.sha256(archive));
        PackagedToolRepair.repair(original, archive, target, manifest, (n,p) -> true);
        assertFalse(Files.exists(target));
        Files.delete(original.resolve("node.exe"));
        PackagedToolRepair.repair(original, archive, target, manifest, (n,p) -> true);
        assertEquals("verified node", Files.readString(target.resolve("node.exe")));
        Files.writeString(target.resolve("node.exe"), "broken");
        PackagedToolRepair.repair(original, archive, target, manifest, (n,p) -> true);
        assertEquals("verified node", Files.readString(target.resolve("node.exe")));
        Files.delete(archive); // A ready runtime does not need network or the archive.
        PackagedToolRepair.repair(original, archive, target, manifest, (n,p) -> true);
        assertThrows(java.io.IOException.class, () -> PackagedToolRepair.repair(original, archive, target, manifest, (n,p) -> false));
        assertEquals("verified node", Files.readString(target.resolve("node.exe")));
    }
    @Test void corruptArchiveOrFailedProbePreservesExistingDirectory() throws Exception {
        Path archive = root.resolve("repair.zip"), target = root.resolve("private");
        Files.createDirectories(target); Files.writeString(target.resolve("node.exe"), "old data");
        var manifest = new ObjectMapper().createObjectNode();
        Path source = root.resolve("source"); Files.writeString(source, "new data");
        manifest.putObject("files").put("node.exe", RuntimeFiles.sha256(source));
        manifest.putArray("executables").add("node.exe");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("node.exe")); zip.write(Files.readAllBytes(source)); zip.closeEntry();
        }
        manifest.put("sha256", RuntimeFiles.sha256(archive));
        assertThrows(java.io.IOException.class, () -> PackagedToolRepair.repair(root.resolve("absent"), archive, target, manifest, (n,p) -> false));
        assertEquals("old data", Files.readString(target.resolve("node.exe")));
        Files.writeString(archive, "broken archive");
        assertThrows(java.io.IOException.class, () -> PackagedToolRepair.repair(root.resolve("absent"), archive, target, manifest, (n,p) -> true));
        assertEquals("old data", Files.readString(target.resolve("node.exe")));
        try (var files = Files.list(root)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".tool-repair-"))); }
    }
}
