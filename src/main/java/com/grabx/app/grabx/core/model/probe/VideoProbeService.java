package com.grabx.app.grabx.core.model.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grabx.app.grabx.util.VideoQualityUtils;
import com.grabx.app.grabx.util.YtDlpManager;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Reads the available video heights from one yt-dlp JSON probe. */
public final class VideoProbeService {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Set<Integer> probeHeights(String url) {
        if (url == null || url.isBlank()) return Set.of();
        try {
            String json = YtDlpManager.run(List.of(
                    "--no-warnings",
                    "--no-playlist",
                    "-J",
                    "--encoding", "utf-8",
                    url.trim()
            ));
            com.grabx.app.grabx.util.MediaInfoCache.SHARED.remember(url, json);
            return parseHeights(json);
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    public Set<Integer> parseHeights(String output) {
        if (output == null || output.isBlank()) return Set.of();
        try {
            String json = output;
            int firstObject = json.indexOf('{');
            if (firstObject > 0) json = json.substring(firstObject);
            if (!json.trim().startsWith("{")) return Set.of();

            JsonNode formats = objectMapper.readTree(json).get("formats");
            if (formats == null || !formats.isArray()) return Set.of();

            Set<Integer> heights = new TreeSet<>();
            for (JsonNode format : formats) {
                if ("none".equals(format.path("vcodec").asText())
                        || format.path("has_drm").asBoolean()) continue;
                int normalized = VideoQualityUtils.qualityHeight(format.path("width").asInt(-1),
                        format.path("height").asInt(-1), format.path("format_note").asText(""));
                if (normalized > 0) heights.add(normalized);
            }
            return heights;
        } catch (Exception ignored) {
            return Set.of();
        }
    }
}
