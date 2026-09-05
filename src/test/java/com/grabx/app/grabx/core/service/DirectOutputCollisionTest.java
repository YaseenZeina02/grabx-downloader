package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectOutputCollisionTest {
    @TempDir Path directory;

    @Test void mimeFallbackCannotReplaceMeaningfulFilename() {
        assertEquals("The.Mongoose.2026.mp4", DownloadRunner.selectDirectFilename(null, "video/mp4", "download.mp4", "https://example.com/The.Mongoose.2026.mp4?token=x"));
        assertEquals("Movie title.mp4", DownloadRunner.selectDirectFilename(null, "video/mp4", "Movie title", "https://example.com/random"));
        assertEquals("Chosen.mp4", DownloadRunner.selectDirectFilename(null, "video/mp4", "Chosen.mp4", "https://example.com/random"));
    }


    @Test void newDownloadDoesNotReuseOldSegmentFilesEvenWithoutSegmentZero() throws Exception {
        Path oldPart = directory.resolve("download.mp4.grabx.part.3");
        Files.write(oldPart, new byte[]{1, 2, 3});
        Files.write(directory.resolve("download (2).mp4.grabx.part.15"), new byte[]{4});
        assertEquals(directory.resolve("download (3).mp4"), DownloadRunner.uniqueDirectOutput(directory, "download.mp4"));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(oldPart));
    }

    @Test void unusedNameIsKeptAndSinglePartIsAlsoProtected() throws Exception {
        assertEquals(directory.resolve("video.mp4"), DownloadRunner.uniqueDirectOutput(directory, "video.mp4"));
        Files.write(directory.resolve("video.mp4.grabx.part"), new byte[]{1});
        assertEquals(directory.resolve("video (2).mp4"), DownloadRunner.uniqueDirectOutput(directory, "video.mp4"));
    }
}
