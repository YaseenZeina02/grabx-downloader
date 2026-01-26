package com.grabx.app.grabx.thumbs;

import javafx.scene.image.Image;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

public final class ThumbnailCacheManager {

    private static final ConcurrentHashMap<String, Image> MEMORY_CACHE =
            new ConcurrentHashMap<>();

    private static final Path THUMBS_DIR = resolveThumbsDir();

    private ThumbnailCacheManager() {}

    /* ===============================
       Public API
       =============================== */

    /**
     * Load thumbnail FAST:
     * 1) Memory cache
     * 2) Disk cache
     * 3) (optional later) Network fetch
     */
    public static Image loadCached(String url) {
        if (url == null || url.isBlank()) return null;

        // memory
        Image mem = MEMORY_CACHE.get(url);
        if (mem != null) return mem;

        // disk
        Path p = getThumbPath(url);
        if (Files.exists(p)) {
            Image img = new Image(p.toUri().toString(), true);
            MEMORY_CACHE.put(url, img);
            return img;
        }

        return null; // not cached yet
    }

    /**
     * Download & cache thumbnail ONCE (background thread)
     */
    public static void fetchAndCacheAsync(String url, String thumbUrl, Runnable onDone) {
        if (url == null || thumbUrl == null) return;

        Path out = getThumbPath(url);
        if (Files.exists(out)) return;

        new Thread(() -> {
            try {
                Files.createDirectories(out.getParent());

                try (InputStream in = new URL(thumbUrl).openStream()) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }

                if (onDone != null) onDone.run();

            } catch (Exception ignored) {}
        }, "thumb-cache").start();
    }

    /* ===============================
       Helpers
       =============================== */

    private static Path getThumbPath(String url) {
        return THUMBS_DIR.resolve(sha1(url) + ".jpg");
    }

    private static Path resolveThumbsDir() {
        String home = System.getProperty("user.home");

        // macOS
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "GrabX", "thumbs");
        }

        // Windows
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) {
                return Paths.get(appdata, "GrabX", "thumbs");
            }
        }

        // Linux / fallback
        return Paths.get(home, ".grabx", "thumbs");
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
    public static Path getCachedPath(String url) {
        if (url == null || url.isBlank()) return null;
        Path p = getThumbPath(url);
        try {
            return Files.exists(p) ? p : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void fetchAndCacheBlocking(String key, String thumbUrl) {
        if (key == null || key.isBlank() || thumbUrl == null || thumbUrl.isBlank()) return;

        Path out = getThumbPath(key);
        try {
            if (Files.exists(out)) return;
            Files.createDirectories(out.getParent());
            try (InputStream in = new URL(thumbUrl).openStream()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {}
    }
}