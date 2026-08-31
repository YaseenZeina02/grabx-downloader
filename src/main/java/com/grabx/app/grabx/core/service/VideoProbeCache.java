package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.util.YouTubeUrls;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** In-memory, session-only cache for video format probes. */
public final class VideoProbeCache {
    private static final int MAX_ENTRIES = 100;
    private static final long TTL_MS = 30L * 60L * 1000L;

    public record Result(Set<Integer> heights, long createdAtMs) {
        public Result {
            heights = heights == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(heights));
        }

        private boolean isFresh(long now) {
            return now - createdAtMs <= TTL_MS;
        }
    }

    private final Object cacheLock = new Object();
    private final LinkedHashMap<String, Result> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "video-probe-cache");
        thread.setDaemon(true);
        return thread;
    });

    public CompletableFuture<Result> get(String url, Supplier<Set<Integer>> loader) {
        if (loader == null) return CompletableFuture.completedFuture(new Result(Set.of(), System.currentTimeMillis()));

        String key = cacheKey(url);
        Result cached = findFresh(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        CompletableFuture<Result> future = inFlight.computeIfAbsent(key, ignored ->
                CompletableFuture.supplyAsync(() -> {
                    Set<Integer> heights = loader.get();
                    Result loaded = new Result(heights, System.currentTimeMillis());
                    if (!loaded.heights().isEmpty()) put(key, loaded);
                    return loaded;
                }, executor)
        );
        future.whenComplete((result, error) -> inFlight.remove(key, future));
        return future;
    }

    public String cacheKey(String url) {
        String normalized = YouTubeUrls.normalizeSingleVideoUrl(url);
        String videoId = YouTubeUrls.extractVideoId(normalized);
        if (videoId != null && !videoId.isBlank()) return "youtube:" + videoId;
        return normalized == null ? "" : normalized.trim();
    }

    public void clear() {
        synchronized (cacheLock) {
            cache.clear();
        }
    }

    private Result findFresh(String key) {
        synchronized (cacheLock) {
            Result result = cache.get(key);
            if (result == null) return null;
            if (result.isFresh(System.currentTimeMillis())) return result;
            cache.remove(key);
            return null;
        }
    }

    private void put(String key, Result result) {
        synchronized (cacheLock) {
            cache.put(key, result);
            while (cache.size() > MAX_ENTRIES) {
                String eldest = cache.keySet().iterator().next();
                cache.remove(eldest);
            }
        }
    }
}
