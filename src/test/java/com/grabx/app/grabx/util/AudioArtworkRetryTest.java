package com.grabx.app.grabx.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioArtworkRetryTest {
    @TempDir Path directory;

    @Test void refreshesOnlyAudioArtworkWithoutForcingMediaOverwrite() {
        assertEquals("--no-force-overwrites", YtDlpOptions.overwriteArgument(true));
        assertEquals("--no-overwrites", YtDlpOptions.overwriteArgument(false));
    }

    @Test void nativeRetryIgnoresEmptyOldCoverAndEmbedsFreshArtwork() throws Exception {
        String ffmpeg = System.getenv("GRABX_TEST_FFMPEG");
        String ytdlp = System.getenv("GRABX_TEST_YTDLP");
        assumeTrue(ffmpeg != null && ytdlp != null);
        Path source = directory.resolve("source.webm");
        Path cover = directory.resolve("source.webp");
        assertEquals(0, run(List.of(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "sine=duration=1",
                "-c:a", "libopus", source.toString())).code());
        assertEquals(0, run(List.of(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=64x64",
                "-frames:v", "1", cover.toString())).code());
        byte[] original = Files.readAllBytes(source);
        Path info = directory.resolve("info.json");
        new ObjectMapper().writeValue(info.toFile(), Map.of("id", "fixture", "title", "Audio fixture",
                "url", source.toUri().toString(), "ext", "webm", "vcodec", "none", "acodec", "opus",
                "thumbnail", cover.toUri().toString(), "extractor", "generic", "extractor_key", "Generic"));
        Path brokenCover = Files.write(directory.resolve("out [audio].webp"), new byte[0]);
        var command = new ArrayList<>(List.of(ytdlp, "--ignore-config", "--enable-file-urls",
                "--load-info-json", info.toString(), "--no-overwrites", "--continue",
                "-o", directory.resolve("out [audio].%(ext)s").toString(),
                "-x", "--audio-quality", "320K", "--audio-format", "mp3", "--add-metadata",
                "--embed-thumbnail", "--convert-thumbnails", "jpg",
                "--postprocessor-args", "ffmpeg:-id3v2_version 3", "--ffmpeg-location", ffmpeg));
        Result broken = run(command);
        assertNotEquals(0, broken.code());
        assertTrue(broken.output().contains("Postprocessing: Error opening output files: Invalid argument"), broken.output());
        command.set(command.indexOf("--no-overwrites"), YtDlpOptions.overwriteArgument(true));
        Result retry = run(command);
        assertEquals(0, retry.code(), retry.output());
        assertTrue(retry.output().contains("has already been downloaded"), retry.output());
        assertFalse(Files.exists(brokenCover)); // yt-dlp removes its source cover after embedding.
        assertArrayEquals(original, Files.readAllBytes(source));
        Path output = directory.resolve("out [audio].mp3");
        assertTrue(Files.size(output) > 0);
        Result probe = run(List.of(ffmpeg, "-hide_banner", "-i", output.toString()));
        assertTrue(probe.output().contains("Audio: mp3"), probe.output());
        assertTrue(probe.output().contains("attached pic"), probe.output());
    }

    private record Result(int code, String output) { }

    private Result run(List<String> command) throws Exception {
        Path log = Files.createTempFile(directory, "process-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Local fixture timed out");
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
