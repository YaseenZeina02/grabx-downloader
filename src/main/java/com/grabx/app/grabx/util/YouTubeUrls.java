package com.grabx.app.grabx.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** URL parsing and normalization helpers shared by the YouTube flows. */
public final class YouTubeUrls {
    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";

    private YouTubeUrls() {}

    public static String watchUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        return WATCH_URL + videoId;
    }

    public static String thumbnailUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        return "https://i.ytimg.com/vi/" + videoId.trim() + "/hqdefault.jpg";
    }

    public static String extractVideoId(String url) {
        if (url == null) return null;
        String value = url.trim();
        if (value.isEmpty()) return null;

        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host != null) {
                host = host.toLowerCase(Locale.ROOT);
                if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
                    return firstPathSegment(uri.getPath());
                }
                if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
                    String pathId = idFromYouTubePath(uri.getPath());
                    return pathId != null ? pathId : queryParameter(uri.getRawQuery(), "v");
                }
            }
        } catch (Exception ignored) {
            // Preserve the previous best-effort handling of malformed URLs.
        }

        return extractVideoIdLegacy(value);
    }

    public static String normalizeSingleVideoUrl(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.isBlank()) return value;

        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null) return value;
            host = host.toLowerCase(Locale.ROOT);

            if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
                String id = firstPathSegment(uri.getPath());
                return id == null ? value : watchUrl(id);
            }
            if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) return value;

            String pathId = idFromYouTubePath(uri.getPath());
            if (pathId != null) return watchUrl(pathId);

            String id = queryParameter(uri.getRawQuery(), "v");
            return id == null ? value : watchUrl(id);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String idFromYouTubePath(String path) {
        if (path == null) return null;
        if (path.startsWith("/shorts/")) return firstPathSegment(path.substring("/shorts".length()));
        if (path.startsWith("/embed/")) return firstPathSegment(path.substring("/embed".length()));
        return null;
    }

    private static String firstPathSegment(String path) {
        if (path == null) return null;
        String value = path.startsWith("/") ? path.substring(1) : path;
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String queryParameter(String query, String name) {
        if (query == null || query.isBlank()) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals >= 0 ? part.substring(0, equals) : part;
            if (!name.equals(key)) continue;
            String value = equals >= 0 ? part.substring(equals + 1) : "";
            value = URLDecoder.decode(value, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private static String extractVideoIdLegacy(String value) {
        for (String marker : new String[]{"youtu.be/", "/shorts/", "/embed/", "v="}) {
            int start = value.indexOf(marker);
            if (start < 0) continue;
            String id = value.substring(start + marker.length());
            int end = id.length();
            for (char separator : new char[]{'?', '&', '#', '/'}) {
                int position = id.indexOf(separator);
                if (position >= 0) end = Math.min(end, position);
            }
            id = id.substring(0, end).trim();
            if (!id.isEmpty()) return id;
        }
        return null;
    }
}
