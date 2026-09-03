package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadRuntimeUtilsTest {
    @TempDir
    Path tempDir;

    @Test
    void recognizesAudioDestinations() {
        assertTrue(DownloadRuntimeUtils.isAudioStreamFromDestinationLine("[ExtractAudio] Destination: song.mp3"));
        assertTrue(DownloadRuntimeUtils.isAudioStreamFromDestinationLine("[download] Destination: title.f140.m4a"));
        assertFalse(DownloadRuntimeUtils.isAudioStreamFromDestinationLine("[download] Destination: title.f137.mp4"));
        assertTrue(DownloadRuntimeUtils.isAudioStreamFromDestinationLine("[download] Destination: song.opus"));
    }

    @Test
    void keepsThumbnailEmbeddingRulesStable() {
        assertTrue(DownloadRuntimeUtils.supportsAudioThumbnailEmbedding("MP3"));
        assertTrue(DownloadRuntimeUtils.supportsAudioThumbnailEmbedding("m4a"));
        assertFalse(DownloadRuntimeUtils.supportsAudioThumbnailEmbedding("wav"));
    }

    @Test
    void parsesCountersWithoutThrowing() {
        assertEquals(1234L, DownloadRuntimeUtils.parseLongSafe(" 1234 "));
        assertEquals(0L, DownloadRuntimeUtils.parseLongSafe("NA"));
        assertEquals(0L, DownloadRuntimeUtils.parseLongSafe("bad"));
    }

    @Test
    void formatsDecimalSizesAndSpeeds() {
        assertEquals("1.5 MB", DownloadRuntimeUtils.formatBytesDecimal(1_500_000));
        assertEquals("2.5 MB/S", DownloadRuntimeUtils.normalizeSpeedUnit("2.5 MiB/s"));
        assertEquals("37.7 MB / 164.9 MB source", DownloadRuntimeUtils.formatTransferSize(37_700_000, 164_900_000, true));
        assertEquals("37.7 MB / 164.9 MB", DownloadRuntimeUtils.formatTransferSize(37_700_000, 164_900_000, false));
    }

    @Test
    void labelsPostProcessingAsASeparatePhase() {
        assertEquals(
                "Converting audio...",
                DownloadRuntimeUtils.postProcessStatus("[ExtractAudio] Destination: song.mp3")
        );
        assertEquals(
                "Merging audio and video...",
                DownloadRuntimeUtils.postProcessStatus("[Merger] Merging formats into \"video.mp4\"")
        );
        assertEquals(
                "Finalizing...",
                DownloadRuntimeUtils.postProcessStatus("Deleting original file source.webm")
        );
        assertEquals(null, DownloadRuntimeUtils.postProcessStatus("[download] 50%"));
    }

    @Test
    void choosesNextAvailableFilenameAcrossDifferentExtensions() throws Exception {
        Files.writeString(tempDir.resolve("Song [audio].mp3"), "first");
        Files.writeString(tempDir.resolve("Song [audio] (1).m4a"), "second");

        assertEquals(
                tempDir.resolve("Song [audio] (2).%(ext)s").toString(),
                DownloadRuntimeUtils.uniqueOutputTemplate(
                        tempDir, tempDir.resolve("Song [audio].webm").toString()
                )
        );
    }

    @Test
    void keepsUnsuffixedFilenameWhenNoCollisionExists() {
        assertEquals(
                tempDir.resolve("Fresh title.%(ext)s").toString(),
                DownloadRuntimeUtils.uniqueOutputTemplate(
                        tempDir, tempDir.resolve("Fresh title.mp4").toString()
                )
        );
    }
}
