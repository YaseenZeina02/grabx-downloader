package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.util.YouTubeUrls;
import com.grabx.app.grabx.util.YtDlpManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Session cache and bounded executor for on-demand yt-dlp size probes. */
public final class VideoSizeService {
    private static final int MAX_CACHE_ENTRIES = 128;
    @FunctionalInterface
    interface CommandRunner {
        String run(List<String> arguments) throws Exception;
    }

    private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Long>> inFlight = new ConcurrentHashMap<>();
    private final CommandRunner commandRunner;
    private final ExecutorService executor;

    public VideoSizeService() {
        this(YtDlpManager::run);
    }

    VideoSizeService(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
        int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable, "video-size-probe");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public Long getCached(String url, String mode, String quality) {
        return cache.get(cacheKey(url, mode, quality));
    }

    public CompletableFuture<Long> probeAsync(String url, String mode, String quality, String selector) {
        String key = cacheKey(url, mode, quality);
        Long cached = cache.get(key);
        if (cached != null && cached > 0) return CompletableFuture.completedFuture(cached);

        CompletableFuture<Long> future;
        try {
            future = inFlight.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(
                    () -> probeAndCache(key, url, selector), executor));
        } catch (RuntimeException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
        future.whenComplete((result, error) -> inFlight.remove(key, future));
        return future;
    }

    public void shutdown() {
        inFlight.values().forEach(future -> future.cancel(true));
        inFlight.clear();
        executor.shutdownNow();
    }

    static Long parseFirstPositiveBytes(String output) {
        if (output == null) return null;
        for (String line : output.split("\\R")) {
            String value = line == null ? "" : line.trim();
            if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) continue;
            try {
                long bytes = Long.parseLong(value);
                if (bytes > 0) return bytes;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Long probeAndCache(String key, String url, String selector) {
        if (url == null || url.isBlank() || selector == null || selector.isBlank()) return null;
        try {
            String output = commandRunner.run(List.of(
                    "--no-warnings",
                    "--no-playlist",
                    "--skip-download",
                    "-f", selector,
                    "--print", "%(filesize,filesize_approx)s",
                    url.trim()
            ));
            Long bytes = parseFirstPositiveBytes(output);
            if (bytes != null && bytes > 0) {
                if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(key)) {
                    String oldestAvailable = cache.keys().hasMoreElements() ? cache.keys().nextElement() : null;
                    if (oldestAvailable != null) cache.remove(oldestAvailable);
                }
                cache.put(key, bytes);
            }
            return bytes;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String cacheKey(String url, String mode, String quality) {
        String normalizedUrl = YouTubeUrls.normalizeSingleVideoUrl(url);
        return (normalizedUrl == null ? "" : normalizedUrl.trim())
                + "|" + (mode == null ? "" : mode)
                + "|" + (quality == null ? "" : quality);
    }
}
