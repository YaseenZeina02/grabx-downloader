
package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.ui.playlist.PlaylistEntry;
import com.grabx.app.grabx.util.YtDlpManager;
import com.grabx.app.grabx.util.AppLog;
import com.grabx.app.grabx.util.YouTubeUrls;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PlaylistService
 * ----------------
 * خدمة Backend فقط (بدون UI):
 * - تجيب عناصر الـ Playlist بشكل "flat" (سريع وخفيف)
 * - ترجع List<PlaylistEntry> فيها (index, id, title, thumb)
 *
 * الهدف: نطلع من MainController كل منطق yt-dlp الخاص بالـ playlist (مرحلة أولى).
 */
public final class PlaylistService {
    private static final Logger LOG = AppLog.get(PlaylistService.class);

    /**
     * Loads playlist entries using yt-dlp --flat-playlist.
     * Output: one line per entry using: id|title
     */
    public List<PlaylistEntry> loadFlatPlaylist(String playlistUrl) {
        List<PlaylistEntry> out = new ArrayList<>();
        if (playlistUrl == null) return out;

        final String url = playlistUrl.trim();
        if (url.isEmpty()) return out;

        try {
            // Ensure bundled yt-dlp is available
            Path yt = YtDlpManager.ensureAvailable();
            if (yt == null) return out;

            // Use bundled runner so env/encoding is consistent across OS
            String raw = YtDlpManager.run(List.of(
                    "--flat-playlist",
                    "--no-warnings",
                    "--encoding", "utf-8",
                    "--print", "%(id)s|%(title)s",
                    url
            ));

            if (raw == null || raw.isBlank()) return out;

            int index = 0;
            for (String line : raw.split("\\R")) {
                if (line == null) continue;
                String t = line.trim();
                if (t.isEmpty()) continue;

                // ✅ تجاهل سطور الأخطاء/التحذيرات التي قد تخرج من yt-dlp
                // (خصوصاً لما يكون الرابط غلط أو فيه مشكلة اتصال)
                String tl = t.toLowerCase();
                if (tl.startsWith("error:") || tl.startsWith("warning:")) continue;
                if (tl.contains("http error") && tl.contains("bad request")) continue;

                // Expect: <id>|<title>
                int bar = t.indexOf('|');
                String id = (bar >= 0) ? t.substring(0, bar).trim() : t;
                if (id.isEmpty()) continue;

                String lowerId = id.toLowerCase();
                if ("na".equals(lowerId) || "null".equals(lowerId) || "none".equals(lowerId)) continue;

                String title = (bar >= 0) ? t.substring(bar + 1).trim() : "";

                index++;
                PlaylistEntry entry = new PlaylistEntry(
                        index,
                        id,
                        title,
                        YouTubeUrls.thumbnailUrl(id),
                        true
                );

                // Detect unavailable items
                if (looksUnavailable(title)) {
                    try { entry.setUnavailable(true); } catch (Exception ignored) {}
                    try { entry.setUnavailableReason(title); } catch (Exception ignored) {}
                    try { entry.setSelected(false); } catch (Exception ignored) {}
                }

                out.add(entry);
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not load playlist: " + url, e);
        }

        return out;
    }

    private static boolean looksUnavailable(String title) {
        if (title == null) return false;
        String t = title.trim().toLowerCase();
        return t.contains("private video")
                || t.contains("deleted video")
                || t.contains("video unavailable")
                || t.contains("this video is unavailable")
                || t.equals("[private video]")
                || t.equals("[deleted video]");
    }

}
