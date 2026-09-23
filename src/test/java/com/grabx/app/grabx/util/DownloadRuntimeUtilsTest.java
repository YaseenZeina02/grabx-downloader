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
    void resumingUsesTheSavedFilenameWithoutRunningAnotherExtractor() {
        Path saved = tempDir.resolve("Song 100% [audio].webm");
        var plan = DownloadRuntimeUtils.planOutput(tempDir, saved, true, "%(title)s.%(ext)s",
                () -> { throw new AssertionError("Resume must skip the filename probe"); });
        assertEquals(tempDir.resolve("Song 100%% [audio].%(ext)s").toString(), plan.template());
        assertEquals(saved, plan.plannedOutput());
    }

    @Test
    void freshDownloadsStillResolveCollisionsBeforeStarting() throws Exception {
        Files.writeString(tempDir.resolve("Song [audio].mp3"), "existing");
        var plan = DownloadRuntimeUtils.planOutput(tempDir, null, false, "%(title)s.%(ext)s",
                () -> tempDir.resolve("Song [audio].webm").toString());
        assertEquals(tempDir.resolve("Song [audio] (1).%(ext)s").toString(), plan.template());
        assertEquals(tempDir.resolve("Song [audio] (1).webm"), plan.plannedOutput());
    }

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
        assertEquals("37.7 MB / 164.9 MB", DownloadRuntimeUtils.formatTransferSize(37_700_000, 164_900_000));
        assertEquals(60_000_000L, DownloadRuntimeUtils.estimateEncodedAudioBytes("1500", 320_000));
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

    @Test
    void resumeReusesTheRecordedStemEvenWhenItsPartFileExists() throws Exception {
        Path recorded = tempDir.resolve("Song [audio] (3).webm");
        Files.writeString(Path.of(recorded + ".part"), "partial data");

        assertEquals(
                tempDir.resolve("Song [audio] (3).%(ext)s").toString(),
                DownloadRuntimeUtils.resumeOutputTemplate(tempDir, recorded)
        );
    }

    @Test
    void resumesTheOriginalPartAfterRepeatedFormatSuffixesWereSaved() throws Exception {
        Path originalPart = tempDir.resolve("Song [2160p] (1).f401.mp4.part");
        Files.writeString(originalPart, "already downloaded data");
        Path oldRecord = tempDir.resolve("Song [2160p] (1).f401.f401.f401.mp4");
        var plan = DownloadRuntimeUtils.planOutput(tempDir, oldRecord, true, "unused", () -> {
            throw new AssertionError("Resume must not select a new filename");
        });
        assertEquals(tempDir.resolve("Song [2160p] (1).%(ext)s").toString(), plan.template());
        assertEquals(tempDir.resolve("Song [2160p] (1).mp4"), plan.plannedOutput());
        assertEquals("already downloaded data", Files.readString(originalPart));
        for (String format : new String[]{"401", "251", "140-drc"}) {
            assertEquals(tempDir.resolve("Song [2160p] (1).webm"),
                    DownloadRuntimeUtils.mediaOutputPath(tempDir.resolve("Song [2160p] (1).f" + format + ".webm")));
        }
        assertEquals(tempDir.resolve("Title.f401.mp4"),
                DownloadRuntimeUtils.mediaOutputPath(tempDir.resolve("Title.f401.mp4")));
    }

    @Test
    void resolvesThePlannedOutputUsingTheProbedExtension() {
        assertEquals(
                tempDir.resolve("100% Song [audio].webm"),
                DownloadRuntimeUtils.concreteOutputPath(
                        tempDir.resolve("100%% Song [audio].%(ext)s").toString(),
                        tempDir.resolve("100% Song [audio].webm").toString()
                )
        );
    }

    @Test
    void cleanupPreservesOtherNumberedDownloadsThatMayBePaused() throws Exception {
        Path completed = Files.writeString(tempDir.resolve("Song [audio] (3).mp3"), "done");
        Path oldPart = Files.writeString(tempDir.resolve("Song [audio].webm.part"), "old");
        Path numberedPart = Files.writeString(tempDir.resolve("Song [audio] (2).webm.part"), "old");
        Path ownPart = Files.writeString(tempDir.resolve("Song [audio] (3).webm.part"), "own");
        Path thumbnail = Files.writeString(tempDir.resolve("Song [audio] (3).jpg"), "cover");
        Path otherPartial = Files.writeString(tempDir.resolve("Other song.webm.part"), "keep");
        Path olderCompleted = Files.writeString(tempDir.resolve("Song [audio] (1).mp3"), "keep");

        assertEquals(2, DownloadRuntimeUtils.cleanupSupersededArtifacts(completed));
        assertEquals(true, Files.exists(oldPart));
        assertEquals(true, Files.exists(numberedPart));
        assertEquals(false, Files.exists(ownPart));
        assertEquals(false, Files.exists(thumbnail));
        assertEquals(true, Files.exists(otherPartial));
        assertEquals(true, Files.exists(olderCompleted));
        assertEquals(true, Files.exists(completed));
    }
}
