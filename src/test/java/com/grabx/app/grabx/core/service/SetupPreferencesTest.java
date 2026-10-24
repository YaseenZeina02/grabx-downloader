package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.setup.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SetupPreferencesTest {
    @TempDir Path dir;
    @Test void firstSetupSuggestsGrabXWhileReopeningPreservesAnExplicitDefault() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        new DownloadFolderPreferences(store).configure(dir.resolve("old-selection").toString(), false);
        var setup = new SetupPreferences(store, dir);
        assertEquals(dir.resolve("Downloads/GrabX").toString(), setup.initialFolder());
        store.putBoolean("grabx.setup.complete.v1", true);
        assertEquals(dir.resolve("old-selection").toString(), setup.initialFolder());
    }
    @Test void suggestedFolderIsCreatedOnlyAtFinishAndTypedMissingPathsAreRejected() throws Exception {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        var setup = new SetupPreferences(store, dir);
        Path suggested = setup.suggestedFolder();
        assertEquals(suggested, setup.validateFolder(suggested.toString()));
        assertFalse(Files.exists(suggested));
        assertFalse(setup.completed());
        var error = assertThrows(SetupPreferences.FolderValidationException.class,
                () -> setup.validateFolder(dir.resolve("typed-typo").toString()));
        assertEquals(SetupPreferences.FolderProblem.NOT_FOUND, error.problem());
        assertFalse(Files.exists(dir.resolve("typed-typo")));
        setup.finish(suggested.toString(), "en", false, 3, true);
        assertTrue(Files.isDirectory(suggested));
        assertEquals(suggested.toString(), new DownloadFolderPreferences(store).configuredFolder());
    }
    @Test void validationRejectsEmptyRelativeInvalidAndFilePathsAndRechecksAtFinish() throws Exception {
        var setup = new SetupPreferences(new SoundFeedbackServiceTest.MemoryPreferences(), dir);
        for (String value : new String[]{"", "   ", "relative-folder", "bad\u0000path"})
            assertThrows(SetupPreferences.FolderValidationException.class, () -> setup.validateFolder(value));
        Path file = Files.writeString(dir.resolve("file.txt"), "fixture");
        assertEquals(SetupPreferences.FolderProblem.NOT_DIRECTORY,
                assertThrows(SetupPreferences.FolderValidationException.class, () -> setup.validateFolder(file.toString())).problem());
        Path folder = Files.createDirectory(dir.resolve("valid"));
        setup.validateFolder(folder.toString());
        Files.delete(folder);
        assertThrows(SetupPreferences.FolderValidationException.class, () -> setup.finish(folder.toString(), "en", false, 3, true));
        assertFalse(setup.completed());
    }
    @Test void finishSavesActualApplicationPreferencesAndSurvivesRestart() throws Exception {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        var setup = new SetupPreferences(store);
        assertFalse(setup.completed());
        setup.finish(dir.toString(), "ar", false, 5, false);
        assertTrue(new SetupPreferences(store).completed());
        assertEquals(dir.toString(), new DownloadFolderPreferences(store).configuredFolder());
        assertFalse(new DownloadFolderPreferences(store).remembersLastFolder());
        assertEquals("ar", new LanguagePreferences(store).code());
        assertEquals(5, new TaskPreferences(store).maxActive());
        assertFalse(store.getBoolean("grabx.sounds.enabled", true));
        try (var files = java.nio.file.Files.list(dir)) { assertEquals(0, files.count()); }
    }
    @Test void invalidDraftCannotMarkSetupCompleteOrOverwriteExistingSettings() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        var folders = new DownloadFolderPreferences(store); folders.configure(dir.toString(), false);
        var setup = new SetupPreferences(store);
        assertThrows(Exception.class, () -> setup.finish(dir.resolve("missing").toString(), "en", true, 3, true));
        assertThrows(Exception.class, () -> setup.finish(dir.toString(), "invalid", true, 3, true));
        assertThrows(Exception.class, () -> setup.finish(dir.toString(), "en", true, 17, true));
        assertFalse(setup.completed()); assertFalse(folders.remembersLastFolder());
    }
    @Test void nativeManifestRestrictsOriginAndEscapesUnicodeAndWindowsPaths() throws Exception {
        String id = "abcdefghijklmnopabcdefghijklmnop";
        var json = new ObjectMapper().readTree(BrowserSetupService.manifest(id, dir.resolve("مسار مع فراغات\\GrabX.exe")));
        assertEquals(1, json.path("allowed_origins").size());
        assertEquals("chrome-extension://" + id + "/", json.path("allowed_origins").get(0).asText());
        assertEquals(dir.resolve("مسار مع فراغات\\GrabX.exe").toString(), json.path("path").asText());
        assertThrows(IllegalArgumentException.class, () -> BrowserSetupService.manifest("../../malicious", dir));
        assertFalse(BrowserSetupService.validId("a".repeat(31)));
        assertFalse(BrowserSetupService.validId("z".repeat(32)));
    }
    @Test void browserLocationsStayDistinctAndLinuxHonorsOnlyAbsoluteXdgRoots() {
        var browser = BrowserSetupService.Browser.EDGE;
        assertEquals(dir.resolve("Library/Application Support/Microsoft Edge/NativeMessagingHosts/com.grabx.browser_bridge.json"),
                BrowserSetupService.manifestPath(browser, "Mac OS X", dir, Map.of()));
        assertEquals(dir.resolve("config/microsoft-edge/NativeMessagingHosts/com.grabx.browser_bridge.json"),
                BrowserSetupService.manifestPath(browser, "Linux", dir, Map.of("XDG_CONFIG_HOME", dir.resolve("config").toString())));
        assertEquals(dir.resolve(".config/microsoft-edge/NativeMessagingHosts/com.grabx.browser_bridge.json"),
                BrowserSetupService.manifestPath(browser, "Linux", dir, Map.of("XDG_CONFIG_HOME", "relative")));
        assertNotEquals(BrowserSetupService.manifestPath(browser, "Windows 11", dir, Map.of()),
                BrowserSetupService.manifestPath(BrowserSetupService.Browser.CHROME, "Windows 11", dir, Map.of()));
    }
}
