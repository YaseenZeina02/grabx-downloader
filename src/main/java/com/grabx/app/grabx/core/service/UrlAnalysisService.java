package com.grabx.app.grabx.core.service;

import java.util.List;
import java.util.Locale;

public final class UrlAnalysisService {
    public enum ContentType {
        VIDEO,
        PLAYLIST,
        DIRECT_FILE,
        UNSUPPORTED
    }

    private static final List<String> DIRECT_EXTENSIONS = List.of(
            ".mp4", ".mkv", ".webm", ".mov", ".mp3", ".m4a", ".wav",
            ".aac", ".flac", ".zip", ".rar", ".7z", ".pdf",
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".csv", ".txt", ".dmg", ".pkg", ".exe", ".msi", ".apk"
    );

    public ContentType analyze(String url) {
        if (!isHttpUrl(url)) return ContentType.UNSUPPORTED;

        String normalized = url.trim().toLowerCase(Locale.ROOT);
        for (String extension : DIRECT_EXTENSIONS) {
            if (normalized.contains(extension + "?") || normalized.endsWith(extension)) {
                return ContentType.DIRECT_FILE;
            }
        }

        boolean hasPlaylistId = normalized.contains("list=");
        boolean looksYouTube = normalized.contains("youtube.com") || normalized.contains("youtu.be");
        boolean hasVideoId = normalized.contains("watch?v=") || normalized.contains("youtu.be/");
        boolean isPlaylistPath = normalized.contains("youtube.com/playlist");

        if (looksYouTube && (isPlaylistPath || (hasPlaylistId && !hasVideoId))) {
            return ContentType.PLAYLIST;
        }
        if (looksYouTube && (hasVideoId || hasPlaylistId)) {
            return ContentType.VIDEO;
        }
        // A generic web page is not a downloadable file. Browser-triggered file
        // downloads bypass this URL-only analyzer and arrive through the native
        // bridge with verified file metadata.
        return ContentType.UNSUPPORTED;
    }

    public boolean isHttpUrl(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }
}
