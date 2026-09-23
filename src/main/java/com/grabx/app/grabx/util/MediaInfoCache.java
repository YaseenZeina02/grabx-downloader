package com.grabx.app.grabx.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.LongSupplier;

/** Short-lived extractor results shared by format analysis, naming, and downloading. */
public final class MediaInfoCache {
    public static final MediaInfoCache SHARED = new MediaInfoCache(System::currentTimeMillis);
    private static final long TTL_MS = 120_000;
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_JSON_CHARS = 2_000_000;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final LongSupplier clock;

    private record Entry(String json, long expiresAt) {}

    MediaInfoCache(LongSupplier clock) { this.clock = clock; }

    public synchronized void remember(String url, String output) {
        if (url == null || output == null || output.length() > MAX_JSON_CHARS) return;
        try {
            int start = output.indexOf('{');
            if (start < 0) return;
            JsonNode parsed = mapper.readTree(output.substring(start));
            if (!(parsed instanceof ObjectNode info) || !info.path("formats").isArray()
                    || info.path("formats").isEmpty() || info.has("entries")
                    || info.path("is_live").asBoolean() || info.path("is_upcoming").asBoolean()
                    || List.of("is_live", "is_upcoming", "post_live").contains(info.path("live_status").asText())
                    || !key(url).equals(key(info.path("webpage_url").asText()))) return;
            // yt-dlp retains formats rejected by its availability check in JSON.
            // Loading that JSON may select them again; never retry a known failure.
            var usableFormats = mapper.createArrayNode();
            for (JsonNode format : info.path("formats")) {
                if (!format.path("__working").isBoolean() || format.path("__working").asBoolean()) {
                    usableFormats.add(format);
                }
            }
            if (usableFormats.isEmpty()) return;
            info.set("formats", usableFormats);
            long now = clock.getAsLong();
            long expiresAt = now + TTL_MS;
            for (JsonNode format : info.path("formats")) {
                String query = URI.create(format.path("url").asText("")).getRawQuery();
                if (query == null) continue;
                for (String part : query.split("&")) {
                    if (part.matches("expire=[0-9]{1,12}")) {
                        expiresAt = Math.min(expiresAt, Long.parseLong(part.substring(7)) * 1000 - 60_000);
                    }
                }
            }
            if (expiresAt <= now) return;
            // Keep all formats so -f can choose audio or a different quality later.
            // Do not carry the probe's selected downloads or output paths forward.
            info.remove(List.of("requested_downloads", "requested_formats", "requested_subtitles",
                    "filepath", "filename", "_filename", "infojson_filename"));
            entries.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
            entries.put(key(url), new Entry(mapper.writeValueAsString(info), expiresAt));
            while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        } catch (Exception ignored) {
            // A cache miss always keeps the normal extractor path available.
        }
    }

    public synchronized void invalidate(String url) { entries.remove(key(url)); }

    /** Replace a failed cached input once, retaining output/format/resume options. */
    public List<String> retryCommand(List<String> command, Input input, int exitCode, String stopReason) {
        if (exitCode == 0 || stopReason != null || input == null || !input.cached()) return null;
        int index = command.indexOf("--load-info-json");
        if (index < 0 || index + 1 >= command.size() || !command.get(index + 1).equals(input.file.toString())) return null;
        invalidate(input.url);
        var retry = new java.util.ArrayList<>(command);
        retry.remove(index + 1);
        retry.remove(index);
        retry.add(input.url);
        return retry;
    }

    public Input openInput(String url) {
        Entry entry;
        synchronized (this) {
            entry = entries.get(key(url));
            if (entry != null && entry.expiresAt <= clock.getAsLong()) {
                entries.remove(key(url));
                entry = null;
            }
        }
        if (entry == null) return new Input(url, null);
        Path file = null;
        try {
            file = Files.createTempFile("grabx-media-", ".info.json");
            Files.writeString(file, entry.json);
            return new Input(url, file);
        } catch (IOException exception) {
            if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return new Input(url, null);
        }
    }

    private static String key(String url) {
        String normalized = YouTubeUrls.normalizeSingleVideoUrl(url);
        return normalized == null ? "" : normalized;
    }

    /** Each subprocess owns its temporary input; no signed media URLs are persisted in history. */
    public record Input(String url, Path file) implements AutoCloseable {
        public boolean cached() { return file != null; }
        public List<String> arguments() {
            // Passing both the JSON and the URL would download the video twice.
            return cached() ? List.of("--load-info-json", file.toString()) : List.of(url);
        }
        @Override public void close() {
            if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }
}
