package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Offline check against a real yt-dlp; enabled with GRABX_TEST_YTDLP=/path/to/yt-dlp. */
class MediaInfoCacheIntegrationTest {
    @TempDir Path directory;

    @Test
    void downloadsFromCachedFormatsAndRefreshesAnExpiredStreamThroughThePage() throws Exception {
        String executable = System.getenv("GRABX_TEST_YTDLP");
        assumeTrue(executable != null && !executable.isBlank(), "Set GRABX_TEST_YTDLP for the offline tool check");
        byte[] payload = "GrabX local media fixture".getBytes(StandardCharsets.UTF_8);
        AtomicInteger pageRequests = new AtomicInteger();
        try (var server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            String origin = "http://127.0.0.1:" + server.getLocalPort();
            Thread serving = new Thread(() -> {
                while (!server.isClosed()) {
                    try (var socket = server.accept()) {
                        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String first = reader.readLine();
                        if (first == null) continue;
                        String target = first.split(" ")[1];
                        while (true) { String header = reader.readLine(); if (header == null || header.isEmpty()) break; }
                        boolean page = target.equals("/watch");
                        boolean expired = target.equals("/expired.mp4");
                        if (page) pageRequests.incrementAndGet();
                        byte[] body = page ? "<html><title>Fixture</title><video src='/fresh.mp4'></video></html>".getBytes(StandardCharsets.UTF_8) : payload;
                        String header = "HTTP/1.1 " + (expired ? "403 Forbidden" : "200 OK") + "\r\nContent-Type: "
                                + (page ? "text/html" : "video/mp4") + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                        socket.getOutputStream().write(header.getBytes(StandardCharsets.UTF_8));
                        if (!first.startsWith("HEAD ")) socket.getOutputStream().write(body);
                    } catch (Exception ignored) { }
                }
            }, "media-cache-fixture");
            serving.setDaemon(true);
            serving.start();
            String page = origin + "/watch";
            try {
                for (boolean expired : new boolean[]{false, true}) {
                    String json = """
                            {"id":"fixture","title":"Fixture","webpage_url":"%s/watch","extractor":"generic",
                             "extractor_key":"Generic","formats":[{"format_id":"http","url":"%s/%s.mp4",
                             "ext":"mp4","vcodec":"avc1","acodec":"aac","protocol":"http"}]}
                            """.formatted(origin, origin, expired ? "expired" : "fresh");
                    MediaInfoCache.SHARED.remember(page, json);
                    Path output = directory.resolve(expired ? "refreshed.mp4" : "cached.mp4");
                    try (var input = MediaInfoCache.SHARED.openInput(page)) {
                        assertTrue(input.cached());
                        var command = new ArrayList<>(List.of(executable, "--ignore-config", "--no-warnings",
                                "--no-playlist", "--no-check-formats", "--continue", "--no-overwrites",
                                "-f", "best", "-o", output.toString()));
                        command.addAll(input.arguments());
                        Path log = directory.resolve(expired ? "refreshed.log" : "cached.log");
                        while (true) {
                            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                            try {
                                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Local fixture timed out");
                                var retry = MediaInfoCache.SHARED.retryCommand(command, input, process.exitValue(), null);
                                if (retry != null) {
                                    command = new ArrayList<>(retry);
                                    continue;
                                }
                                assertEquals(0, process.exitValue(), Files.readString(log));
                                assertArrayEquals(payload, Files.readAllBytes(output));
                                if (expired) assertTrue(pageRequests.get() > 0, "Expired media must re-extract the page");
                                else assertEquals(0, pageRequests.get(), "Fresh media must skip page extraction");
                                break;
                            } finally { if (process.isAlive()) process.destroyForcibly(); }
                        }
                    }
                }
            } finally { MediaInfoCache.SHARED.invalidate(page); }
        }
    }

    @Test
    void cachedAnalysisNamesAudioAndDifferentVideoQualitiesWithoutExtractingThePage() throws Exception {
        String executable = System.getenv("GRABX_TEST_YTDLP");
        assumeTrue(executable != null && !executable.isBlank(), "Set GRABX_TEST_YTDLP for the offline tool check");
        String url = "https://example.invalid/watch/fixture";
        String json = """
                {"id":"fixture","title":"Cached fixture","webpage_url":"https://example.invalid/watch/fixture",
                 "extractor":"generic","extractor_key":"Generic","duration":10,"formats":[
                  {"format_id":"a","url":"https://example.invalid/audio.m4a","ext":"m4a",
                   "vcodec":"none","acodec":"aac","abr":128,"protocol":"https"},
                  {"format_id":"v360","url":"https://example.invalid/video360.mp4","ext":"mp4",
                   "vcodec":"avc1","acodec":"none","height":360,"width":640,"tbr":500,"protocol":"https"},
                  {"format_id":"v720","url":"https://example.invalid/video720.mp4","ext":"mp4",
                   "vcodec":"avc1","acodec":"none","height":720,"width":1280,"tbr":1000,"protocol":"https"},
                  {"format_id":"broken","url":"https://example.invalid/broken.mp4","ext":"mp4",
                   "vcodec":"avc1","acodec":"none","height":2160,"width":3840,"tbr":9000,
                   "protocol":"https","__working":false}]}
                """;
        MediaInfoCache.SHARED.remember(url, json);
        try {
            Path yt = Path.of(executable);
            String audio = DownloadRuntimeUtils.probeOutputFilename(yt, url, "bestaudio/best", directory,
                    "%(title)s [audio].%(ext)s");
            assertEquals(directory.resolve("Cached fixture [audio].m4a").toString(), audio);
            assertEquals(directory.resolve("Cached fixture [720p].mp4").toString(),
                    DownloadRuntimeUtils.probeOutputFilename(yt, url, "bestvideo", directory,
                            "%(title)s [%(height)sp].%(ext)s"));
            for (int height : new int[]{360, 720}) {
                String video = DownloadRuntimeUtils.probeOutputFilename(yt, url,
                        "bestvideo[height<=" + height + "]", directory, "%(title)s [%(height)sp].%(ext)s");
                assertEquals(directory.resolve("Cached fixture [" + height + "p].mp4").toString(), video);
            }
            // The .invalid URLs cannot be extracted: successful names prove the
            // Java subprocess invocation used the metadata, without downloading.
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        } finally {
            MediaInfoCache.SHARED.invalidate(url);
        }
    }
}
