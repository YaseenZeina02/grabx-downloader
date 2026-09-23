package com.grabx.app.grabx.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VideoQualityIntegrationTest {
    @TempDir Path directory;

    @Test void portraitSelectionKeepsRequestedQualityAndPrefersNativeCodecs() throws Exception {
        String tool = System.getenv("GRABX_TEST_YTDLP");
        assumeTrue(tool != null);
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode().put("id", "DodLg1SxmWI").put("title", "Portrait")
                .put("extractor", "youtube").put("extractor_key", "Youtube")
                .put("webpage_url", "https://www.youtube.com/watch?v=DodLg1SxmWI");
        var formats = root.putArray("formats");
        for (int quality : new int[]{144,240,360,480,720,1080,2160}) {
            for (String codec : quality == 2160 ? List.of("av01") : List.of("avc1", "av01")) {
                formats.addObject().put("format_id", quality + codec).put("width", quality)
                        .put("height", quality * 4 / 3).put("format_note", quality + "p").put("aspect_ratio", .75)
                        .put("vcodec", codec).put("acodec", "none").put("ext", "mp4")
                        .put("url", "https://example.invalid/video.mp4").put("protocol", "https");
            }
        }
        for (String codec : List.of("mp4a.40.2", "opus")) formats.addObject().put("format_id", codec)
                .put("vcodec", "none").put("acodec", codec).put("ext", "m4a")
                .put("url", "https://example.invalid/audio.m4a").put("protocol", "https");
        Path json = directory.resolve("info.json");
        Files.writeString(json, mapper.writeValueAsString(root));
        for (int quality : new int[]{144,240,360,480,720,1080,2160}) {
            var args = new ArrayList<>(List.of(tool, "--ignore-config", "--no-warnings", "--skip-download",
                    "--load-info-json", json.toString(), "-f", VideoQualityUtils.formatSelectorForHeight(quality),
                    "--print", "%(width)s|%(vcodec)s|%(acodec)s"));
            args.addAll(YtDlpOptions.argumentsFor(null));
            args.addAll(YtDlpOptions.videoFormatArguments());
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            assertEquals(0, process.waitFor(), output);
            assertEquals(quality + "|" + (quality == 2160 ? "av01" : "avc1") + "|mp4a.40.2", output);
        }
        String url = root.path("webpage_url").asText();
        MediaInfoCache.SHARED.remember(url, Files.readString(json));
        try {
            String name = DownloadRuntimeUtils.probeOutputFilename(Path.of(tool), url,
                    VideoQualityUtils.formatSelectorForHeight(480), directory, "%(title)s [%(height)sp].%(ext)s");
            assertTrue(name.contains("[480p]"), name);
            assertFalse(name.contains("[640p]"), name);
        } finally { MediaInfoCache.SHARED.invalidate(url); }
    }
}
