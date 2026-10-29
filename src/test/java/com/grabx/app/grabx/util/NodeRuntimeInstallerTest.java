package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeRuntimeInstallerTest {
    @TempDir Path directory;
    @Test void unsupportedProcessorCannotSelectAnIncompatibleBinary() throws Exception {
        assertEquals("darwin-arm64", NodeRuntimeInstaller.platform("Mac OS X", "aarch64"));
        assertEquals("darwin-x64", NodeRuntimeInstaller.platform("Darwin", "x86_64"));
        assertEquals("linux-arm64", NodeRuntimeInstaller.platform("Linux", "arm64"));
        assertEquals("linux-x64", NodeRuntimeInstaller.platform("Linux", "amd64"));
        assertEquals("win-arm64", NodeRuntimeInstaller.platform("Windows 11", "aarch64"));
        assertEquals("win-x64", NodeRuntimeInstaller.platform("Windows 10", "amd64"));
        assertThrows(IOException.class, () -> NodeRuntimeInstaller.platform("Windows 10", "x86"));
    }
    private byte[] archive() throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (String name : new String[]{"node.exe", "LICENSE", "../../outside.txt"}) {
                zip.putNextEntry(new ZipEntry("node-v" + NodeRuntimeInstaller.VERSION + "-win-x64/" + name));
                zip.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
    private NodeRuntimeInstaller.Fetch fetch(byte[] payload, String checksum) {
        return (uri, output, limit) -> {
            if (uri.getPath().endsWith("SHASUMS256.txt")) Files.writeString(output,
                    checksum + "  node-v" + NodeRuntimeInstaller.VERSION + "-win-x64.zip\n");
            else Files.write(output, payload);
        };
    }
    @Test void verifiesArchivePreservesLicenseAndExtractsOnlyRequiredFiles() throws Exception {
        byte[] payload = archive(); String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        Path target = directory.resolve("runtime");
        Path binary = NodeRuntimeInstaller.install(target, "win-x64", fetch(payload,digest), Files::isRegularFile);
        assertEquals("node.exe", Files.readString(binary)); assertEquals("LICENSE", Files.readString(target.resolve("LICENSE")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        assertFalse(Files.exists(directory.resolve("outside.txt")));
    }
    @Test void repairsDamagedPrivateRuntimeAndPreservesItWhenDownloadFails() throws Exception {
        byte[] payload = archive(); String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        Path target = directory.resolve("runtime"); Files.createDirectories(target); Files.writeString(target.resolve("node.exe"), "broken");
        assertThrows(IOException.class, () -> NodeRuntimeInstaller.install(target, "win-x64", fetch(payload,"0".repeat(64)), p -> true));
        assertEquals("broken", Files.readString(target.resolve("node.exe")));
        NodeRuntimeInstaller.install(target, "win-x64", fetch(payload,digest), Files::isRegularFile);
        assertEquals("node.exe", Files.readString(target.resolve("node.exe")));
    }
    @Test void checksumMismatchAndUnusableBinaryLeaveNoInstallationOrStagingFiles() throws Exception {
        byte[] payload = archive(); Path target = directory.resolve("runtime");
        assertThrows(IOException.class, () -> NodeRuntimeInstaller.install(target,"win-x64",fetch(payload,"0".repeat(64)),p -> true));
        assertFalse(Files.exists(target));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        assertThrows(IOException.class, () -> NodeRuntimeInstaller.install(target,"win-x64",fetch(payload,digest),p -> false));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
