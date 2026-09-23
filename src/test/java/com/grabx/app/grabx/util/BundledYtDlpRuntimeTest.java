package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class BundledYtDlpRuntimeTest {
    @TempDir Path directory;
    private byte[] archive(String name) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write("fixture executable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_internal/runtime.data"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    @Test void installsTheWholeRuntimeOnceAndReusesItWithoutReextracting() throws Exception {
        byte[] zip = archive("yt-dlp_macos");
        Path target = directory.resolve("runtime");
        Path executable = BundledYtDlpRuntime.install(new ByteArrayInputStream(zip), target, "yt-dlp_macos", hash(zip));
        assertTrue(Files.isExecutable(executable));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(target.resolve("_internal/runtime.data")));
        assertEquals(executable, BundledYtDlpRuntime.install(new ByteArrayInputStream(new byte[0]), target, "yt-dlp_macos", hash(zip)));
    }

    @Test void rejectsCorruptPackagesWithoutLeavingAnInstalledRuntime() throws Exception {
        byte[] zip = archive("yt-dlp_macos");
        assertThrows(IOException.class, () -> BundledYtDlpRuntime.install(new ByteArrayInputStream(zip), directory.resolve("bad"), "yt-dlp_macos", "bad hash"));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsArchivePathsOutsideTheInstallationDirectory() throws Exception {
        byte[] zip = archive("../../escaped");
        assertThrows(IOException.class, () -> BundledYtDlpRuntime.install(new ByteArrayInputStream(zip), directory.resolve("bad"), "yt-dlp_macos", hash(zip)));
        assertFalse(Files.exists(directory.resolve("escaped")));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
