package com.grabx.app.grabx.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Local executable probes only; checking never installs or downloads software. */
public final class SetupDiagnostics {
    public record Check(String name, boolean ready, String detail, String helpUrl) {}
    private SetupDiagnostics() {}
    public static List<Check> inspect() {
        var result = new ArrayList<Check>();
        result.add(new Check("Java", Runtime.version().feature() >= 21,
                "Java " + Runtime.version() + " · " + System.getProperty("os.arch"), "https://adoptium.net/"));
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String ffmpeg = ToolExecutable.version(InstalledLayout.tool("ffmpeg"), "-version");
        if (ffmpeg == null) {
            String saved = FfmpegManager.getPersistedPath();
            if (saved != null) try { ffmpeg = ToolExecutable.version(Path.of(saved), "-version"); } catch (Exception ignored) {}
        }
        if (ffmpeg == null) for (Path path : ToolExecutable.candidates(windows ? "ffmpeg.exe" : "ffmpeg",
                System.getenv(), Path.of(System.getProperty("user.home")), windows)) {
            ffmpeg = ToolExecutable.version(path, "-version");
            if (ffmpeg != null && ffmpeg.startsWith("ffmpeg version")) break;
            ffmpeg = null;
        }
        result.add(new Check("FFmpeg", ffmpeg != null && ffmpeg.startsWith("ffmpeg version"),
                ffmpeg == null ? "Required for merging video and converting audio" : ffmpeg, "https://ffmpeg.org/download.html"));
        String js = null;
        for (var runtime : YtDlpOptions.candidates(System.getenv(), Path.of(System.getProperty("user.home")), windows)) {
            String version = ToolExecutable.version(runtime.path(), "--version");
            if (YtDlpOptions.supportedVersion(runtime.name(), version)) { js = runtime.name() + " · " + version; break; }
        }
        result.add(new Check("YouTube JavaScript", js != null,
                js == null ? "Install Node 22+ or Deno 2.3+ for full YouTube quality support" : js, "https://nodejs.org/en/download"));
        result.add(new Check("yt-dlp", SetupDiagnostics.class.getResource("/tools/yt-dlp/bundled.properties") != null,
                "Included with GrabX; prepared locally on first media download", "https://github.com/yt-dlp/yt-dlp"));
        return List.copyOf(result);
    }
}
