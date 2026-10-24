package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.setup.ExtensionBundleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionBundleServiceTest {
    @TempDir Path dir;
    @Test void freshDeviceGetsTheCompleteBundledExtensionWithoutProjectFiles() throws Exception {
        var service = new ExtensionBundleService(dir.resolve("fresh-home/GrabX/browser-extension"));
        Path exported = service.prepare();
        assertTrue(Files.readString(exported.resolve("manifest.json")).contains("GrabX Download Assistant"));
        for (String file : new String[]{"service-worker.js", "popup.html", "popup.js", "page-assistant.js", "queue-state.js", "queue.js", "icons/grabx-128.png"})
            assertTrue(Files.isRegularFile(exported.resolve(file)), file);
        assertEquals(exported, service.prepare());
        Files.delete(exported.resolve("popup.js"));
        assertEquals(exported, service.prepare());
        assertTrue(Files.isRegularFile(exported.resolve("popup.js")), "Repair must keep the exact path and extension ID");
        try (var files = Files.list(exported.getParent())) { assertEquals(1, files.count(), "No staging/backup leftovers"); }
    }
    @Test void invalidOrTraversalArchiveCannotDamageAnExistingExport() throws Exception {
        var service = new ExtensionBundleService(dir);
        Path current = service.prepare();
        String manifest = Files.readString(current.resolve("manifest.json"));
        assertThrows(IOException.class, () -> service.prepare(new ByteArrayInputStream(archive("../escape.txt", "bad"))));
        assertFalse(Files.exists(dir.resolve("escape.txt")));
        assertThrows(IOException.class, () -> service.prepare(new ByteArrayInputStream(archive("manifest.json", "{}"))));
        assertEquals(manifest, Files.readString(current.resolve("manifest.json")));
    }
    @Test void anUnmanagedDirectoryIsNeverOverwritten() throws Exception {
        Path unrelated = Files.createDirectories(dir.resolve("chromium"));
        Files.writeString(unrelated.resolve("personal.txt"), "keep");
        assertThrows(IOException.class, () -> new ExtensionBundleService(dir).prepare());
        assertEquals("keep", Files.readString(unrelated.resolve("personal.txt")));
    }
    @Test void platformDataLocationsDoNotDependOnADevelopmentCheckout() {
        assertEquals(dir.resolve("Library/Application Support/GrabX"), ExtensionBundleService.dataRoot("Mac OS X", dir, Map.of()));
        assertEquals(dir.resolve("AppData/Local/GrabX"), ExtensionBundleService.dataRoot("Windows 11", dir, Map.of()));
        assertEquals(dir.resolve("custom/GrabX"), ExtensionBundleService.dataRoot("Linux", dir, Map.of("XDG_DATA_HOME", dir.resolve("custom").toString())));
        assertEquals(dir.resolve(".local/share/GrabX"), ExtensionBundleService.dataRoot("Linux", dir, Map.of("XDG_DATA_HOME", "relative")));
    }
    static byte[] archive(String name, String data) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) { zip.putNextEntry(new ZipEntry(name)); zip.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry(); }
        return buffer.toByteArray();
    }
}
