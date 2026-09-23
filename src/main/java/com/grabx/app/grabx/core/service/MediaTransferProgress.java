package com.grabx.app.grabx.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grabx.app.grabx.util.DownloadRuntimeUtils;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tracks the selected streams by ID, including already-downloaded streams on resume. */
final class MediaTransferProgress {
    static final String SELECTION_TEMPLATE =
            "before_dl:gxmeta:%(duration)s|%(requested_formats.:.{format_id,filesize,filesize_approx,tbr,vcodec,acodec})j";
    private final Map<String, Stream> streams = new LinkedHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Stream {
        long size = -1;
        long downloaded;
        boolean audio;
    }

    void select(String json) {
        select(json, 0);
    }

    void select(String json, double duration) {
        streams.clear();
        try {
            JsonNode formats = JSON.readTree(json);
            if (formats == null || !formats.isArray()) return;
            for (JsonNode format : formats) {
                String id = format.path("format_id").asText("");
                if (id.isBlank()) continue;
                Stream stream = new Stream();
                stream.size = format.path("filesize").asLong(-1);
                if (stream.size <= 0) stream.size = format.path("filesize_approx").asLong(-1);
                double bitrate = format.path("tbr").asDouble(0);
                if (stream.size <= 0 && duration > 0 && Double.isFinite(duration)
                        && bitrate > 0 && Double.isFinite(bitrate)) {
                    stream.size = Math.round(bitrate * 1000 * duration / 8);
                }
                stream.audio = "none".equals(format.path("vcodec").asText());
                streams.put(id, stream);
            }
        } catch (Exception ignored) {
            streams.clear();
        }
    }

    boolean combined() { return streams.size() > 1; }

    Snapshot update(String id, long downloaded, long total, boolean finished) {
        return update(id, downloaded, total, -1, finished);
    }

    Snapshot update(String id, long downloaded, long total, long estimatedTotal, boolean finished) {
        Stream stream = streams.get(id);
        if (stream == null) {
            // An unexpected stream means the earlier total is no longer reliable.
            stream = new Stream();
            streams.put(id, stream);
        }
        stream.downloaded = Math.max(stream.downloaded, Math.max(0, downloaded));
        if (total > 0) stream.size = total;
        // Early fragment estimates can describe only a tiny first segment. Keep
        // the larger metadata estimate until the actual stream length is known.
        else if (estimatedTotal > 0) stream.size = Math.max(stream.size, estimatedTotal);
        if (finished && downloaded >= 0) stream.size = downloaded;
        if (stream.size > 0) stream.size = Math.max(stream.size, stream.downloaded);
        return snapshot();
    }

    String phase(String id) {
        Stream stream = streams.get(id);
        return stream != null && stream.audio ? "Downloading audio" : "Downloading video";
    }

    Snapshot snapshot() {
        long downloaded = 0, total = 0;
        boolean known = !streams.isEmpty();
        for (Stream stream : streams.values()) {
            downloaded += stream.downloaded;
            if (stream.size <= 0) known = false;
            else total += stream.size;
        }
        return new Snapshot(downloaded, known ? total : -1);
    }

    record Snapshot(long downloaded, long total) {
        double fraction() { return total > 0 ? Math.min(1.0, (double) downloaded / total) : -1; }
        String sizeText() {
            String current = DownloadRuntimeUtils.formatBytesDecimal(downloaded);
            return total > 0 ? current + " / ≈ " + DownloadRuntimeUtils.formatBytesDecimal(total) : current;
        }
        String eta(String bytesPerSecond) {
            try {
                double speed = Double.parseDouble(bytesPerSecond.trim());
                if (total <= 0 || !Double.isFinite(speed) || speed <= 0) return "";
                long seconds = (long) Math.ceil(Math.max(0, total - downloaded) / speed);
                return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                        : String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
            } catch (Exception ignored) { return ""; }
        }
    }
}
