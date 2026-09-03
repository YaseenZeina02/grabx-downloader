package com.grabx.app.grabx.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grabx.app.grabx.core.model.DownloadRow;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public final class DownloadTitleService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TIMEOUT_MILLIS = 6_000;

    private final Iterable<DownloadRow> rows;
    private final Consumer<Runnable> uiDispatcher;
    private final Consumer<String> statusUpdater;
    private final Function<String, String> titleFetcher;

    public DownloadTitleService(
            Iterable<DownloadRow> rows,
            Consumer<Runnable> uiDispatcher,
            Consumer<String> statusUpdater
    ) {
        this(rows, uiDispatcher, statusUpdater, DownloadTitleService::fetchTitleWithOEmbed);
    }

    DownloadTitleService(
            Iterable<DownloadRow> rows,
            Consumer<Runnable> uiDispatcher,
            Consumer<String> statusUpdater,
            Function<String, String> titleFetcher
    ) {
        this.rows = rows;
        this.uiDispatcher = uiDispatcher == null ? Runnable::run : uiDispatcher;
        this.statusUpdater = statusUpdater;
        this.titleFetcher = titleFetcher;
    }

    public void resolveAsync(DownloadRow row, String mediaUrl) {
        if (row == null || mediaUrl == null || mediaUrl.isBlank()) return;

        Thread worker = new Thread(() -> {
            String fetchedTitle;
            try {
                fetchedTitle = titleFetcher == null ? null : titleFetcher.apply(mediaUrl);
            } catch (Exception ignored) {
                fetchedTitle = null;
            }
            String resolvedTitle = fetchedTitle;
            uiDispatcher.accept(() -> applyResolvedTitle(row, mediaUrl, resolvedTitle));
        }, "title-oembed");
        worker.setDaemon(true);
        worker.start();
    }

    void applyResolvedTitle(DownloadRow row, String mediaUrl, String fetchedTitle) {
        String resolved = fetchedTitle == null ? "" : fetchedTitle.trim();
        if (resolved.isBlank()) resolved = shorten(mediaUrl);
        if (resolved.isBlank()) resolved = "Unknown title";

        // The card title describes the media. Filename collision suffixes belong
        // only to the file on disk and must not leak into the displayed title.
        row.setTitleOnce(resolved);
        if (statusUpdater != null) statusUpdater.accept("Queued: " + resolved);
    }

    String uniqueTitle(String baseTitle, DownloadRow currentRow) {
        String base = baseTitle == null ? "" : baseTitle.trim();
        return base;
    }

    public static String shorten(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > 46 ? trimmed.substring(0, 43) + "..." : trimmed;
    }

    static String parseTitle(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode title = JSON.readTree(json).get("title");
            if (title == null || !title.isTextual()) return null;
            String value = title.asText().trim();
            return value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String fetchTitleWithOEmbed(String mediaUrl) {
        HttpURLConnection connection = null;
        try {
            String encodedUrl = URLEncoder.encode(mediaUrl.trim(), StandardCharsets.UTF_8);
            URI endpoint = URI.create("https://www.youtube.com/oembed?format=json&url=" + encodedUrl);
            connection = (HttpURLConnection) endpoint.toURL().openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("User-Agent", "GrabX/1.0");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return null;
            try (var input = connection.getInputStream()) {
                return parseTitle(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean hasSameDownloadTarget(DownloadRow first, DownloadRow second) {
        if (first == null || second == null) return false;
        return normalizedPath(first.folder).equals(normalizedPath(second.folder))
                && Objects.equals(first.mode, second.mode)
                && Objects.equals(first.quality, second.quality);
    }

    private static String normalizedPath(String folder) {
        if (folder == null || folder.isBlank()) return "";
        try {
            return Path.of(folder).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            return folder.trim();
        }
    }

    private static String getTitle(DownloadRow row) {
        try {
            return row.title == null || row.title.get() == null ? "" : row.title.get();
        } catch (Exception ignored) {
            return "";
        }
    }
}
