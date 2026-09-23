package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MacVideoCompatibilityTest {
    @TempDir Path directory;

    @Test void identifiesIncompatibleTracksAndIgnoresCoverArt() throws Exception {
        var av1 = MacVideoCompatibility.codecs("Stream #0:0: Video: av1 (Main), yuv420p\nStream #0:1: Audio: opus, 48000 Hz");
        assertFalse(av1.copyVideo()); assertFalse(av1.copyAudio());
        var nativeCodecs = MacVideoCompatibility.codecs("Stream #0:0: Video: mjpeg (attached pic)\nStream #0:1: Video: h264 (High), yuv420p\nStream #0:2: Audio: aac (LC)");
        assertTrue(nativeCodecs.copyVideo()); assertTrue(nativeCodecs.copyAudio());
        assertFalse(MacVideoCompatibility.codecs("Stream #0:0: Video: h264 (High 10), yuv420p10le").copyVideo());
        assertThrows(java.io.IOException.class, () -> MacVideoCompatibility.codecs("Invalid data"));
    }

    @Test void convertsOpusPreservesResolutionAndLeavesCompletedNativeVideoUntouched() throws Exception {
        String tool = System.getenv("GRABX_TEST_FFMPEG");
        assumeTrue(tool != null && System.getProperty("os.name").contains("Mac"));
        Path output = directory.resolve("portrait.mp4");
        run(tool, "-y", "-f", "lavfi", "-i", "color=c=blue:s=480x640:r=25:d=1", "-f", "lavfi", "-i", "sine=duration=1",
                "-c:v", "libx264", "-c:a", "libopus", "-strict", "experimental", output.toString());
        AtomicBoolean preparing = new AtomicBoolean();
        assertEquals(output, MacVideoCompatibility.prepare(output, Path.of(tool), p->{}, ()->false, ()->preparing.set(true)));
        assertTrue(preparing.get());
        String metadata = run(tool, "-hide_banner", "-i", output.toString());
        assertTrue(metadata.contains("480x640"));
        var codecs = MacVideoCompatibility.codecs(metadata);
        assertTrue(codecs.copyAudio()); assertTrue(codecs.copyVideo());
        byte[] bytes = Files.readAllBytes(output);
        MacVideoCompatibility.prepare(output, Path.of(tool), p->{}, ()->false, ()->fail("Already native compatible"));
        assertArrayEquals(bytes, Files.readAllBytes(output));
    }

    @Test void cancellationPreservesOriginalAndCleansTemporaryOutput() throws Exception {
        String tool = System.getenv("GRABX_TEST_FFMPEG");
        assumeTrue(tool != null && System.getProperty("os.name").contains("Mac"));
        Path output = directory.resolve("original.mp4");
        run(tool, "-y", "-f", "lavfi", "-i", "color=s=144x192:d=1", "-c:v", "libx264", "-pix_fmt", "yuv420p10le", output.toString());
        byte[] original = Files.readAllBytes(output);
        AtomicBoolean stop = new AtomicBoolean();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MacVideoCompatibility.prepare(output, Path.of(tool), p->{
            if (calls.incrementAndGet() == 2) { stop.set(true); p.destroyForcibly(); }
        }, stop::get, ()->{});
        assertArrayEquals(original, Files.readAllBytes(output));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void incompatibleVideoConvertsWithoutReducingPortraitResolution() throws Exception {
        String tool = System.getenv("GRABX_TEST_FFMPEG");
        assumeTrue(tool != null && System.getProperty("os.name").contains("Mac"));
        Path output = directory.resolve("vp9.mp4");
        run(tool, "-y", "-f", "lavfi", "-i", "color=s=480x640:d=1", "-c:v", "libvpx-vp9", output.toString());
        assertFalse(MacVideoCompatibility.codecs(run(tool, "-hide_banner", "-i", output.toString())).copyVideo());
        MacVideoCompatibility.prepare(output, Path.of(tool), p->{}, ()->false, ()->{});
        String metadata = run(tool, "-hide_banner", "-i", output.toString());
        assertTrue(metadata.contains("480x640"));
        assertEquals("h264", MacVideoCompatibility.codecs(metadata).video());
    }

    private String run(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor();
        return result;
    }
}
