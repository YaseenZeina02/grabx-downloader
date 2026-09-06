package com.grabx.app.grabx.core.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectPartialFilesTest {
    @TempDir Path dir;
    @Test void hidesLegacyPartsWithoutLosingBytesAndCleansOnlyOwnedFiles() throws Exception {
        Path output = Files.writeString(dir.resolve("movie.mp4"), "completed");
        Path legacy = Files.writeString(dir.resolve("movie.mp4.grabx.part.7"), "saved bytes");
        Path other = Files.writeString(dir.resolve("other.mp4.grabx.part.7"), "other");
        Path unrelated = Files.writeString(dir.resolve("movie.mp4.grabx.part.backup"), "backup");
        assertTrue(DirectPartialFiles.hasParts(output));
        DirectPartialFiles.hideLegacyParts(output);
        assertFalse(Files.exists(legacy));
        assertEquals("saved bytes", Files.readString(DirectPartialFiles.part(output, 7)));
        assertTrue(DirectPartialFiles.part(output, -1).getFileName().toString().startsWith("."));
        DirectPartialFiles.cleanup(output);
        assertFalse(DirectPartialFiles.hasParts(output));
        assertEquals("completed", Files.readString(output));
        assertTrue(Files.exists(other));
        assertTrue(Files.exists(unrelated));
    }
    @Test void expiresOnlyWhenEveryOwnedPartIsOld() throws Exception {
        Path output = dir.resolve("movie.mp4");
        long now = System.currentTimeMillis();
        long cutoff = now - java.util.concurrent.TimeUnit.DAYS.toMillis(7);
        Path old = Files.writeString(DirectPartialFiles.part(output, 0), "old");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(cutoff - 1000));
        assertTrue(DirectPartialFiles.expired(output, cutoff));
        Path recent = Files.writeString(DirectPartialFiles.part(output, 1), "recent");
        assertFalse(DirectPartialFiles.expired(output, cutoff));
        Files.setLastModifiedTime(recent, java.nio.file.attribute.FileTime.fromMillis(cutoff - 1000));
        assertTrue(DirectPartialFiles.expired(output, cutoff));
        DirectPartialFiles.cleanup(output);
        assertFalse(DirectPartialFiles.expired(output, cutoff));
    }
    @Test void preservesBothFilesWhenLegacyAndHiddenPartsConflict() throws Exception {
        Path output = dir.resolve("movie.mp4");
        Files.writeString(dir.resolve("movie.mp4.grabx.part.0"), "old");
        Files.writeString(dir.resolve(".movie.mp4.grabx.part.0"), "new");
        assertThrows(java.io.IOException.class, () -> DirectPartialFiles.hideLegacyParts(output));
    }
    @Test void recognizesOfflineFailuresButNotServerRejections() {
        assertTrue(DownloadRunner.isNetworkInterruption(new java.net.UnknownHostException()));
        assertTrue(DownloadRunner.isNetworkInterruption(new java.net.SocketException()));
        assertTrue(DownloadRunner.isNetworkInterruption(new java.io.EOFException()));
        assertFalse(DownloadRunner.isNetworkInterruption(new java.io.IOException("HTTP 403")));
    }
}
