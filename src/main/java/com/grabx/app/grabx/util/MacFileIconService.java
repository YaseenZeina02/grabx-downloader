package com.grabx.app.grabx.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Assigns embedded audio artwork as the native Finder icon on macOS. */
public final class MacFileIconService {
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "aac", "opus", "ogg", "flac", "wav"
    );
    private static final String SET_ICON_SCRIPT = """
            ObjC.import("AppKit");
            function run(argv) {
                var image = $.NSImage.alloc.initWithContentsOfFile($(argv[0]));
                if (!image) return false;
                return $.NSWorkspace.sharedWorkspace.setIconForFileOptions(image, $(argv[1]), 0);
            }
            """;

    private MacFileIconService() {}

    public static boolean applyEmbeddedArtwork(Path audioFile, Path ffmpeg) {
        if (!isMac() || !isSupportedAudio(audioFile) || ffmpeg == null
                || !Files.isRegularFile(audioFile) || !Files.isExecutable(ffmpeg)) return false;

        Path tempDirectory = null;
        try {
            tempDirectory = Files.createTempDirectory("grabx-cover-");
            Path cover = tempDirectory.resolve("cover.jpg");
            Process extract = new ProcessBuilder(
                    ffmpeg.toAbsolutePath().toString(),
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-i", audioFile.toAbsolutePath().toString(),
                    "-map", "0:v:0", "-frames:v", "1", cover.toString()
            ).redirectErrorStream(true).start();
            extract.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (extract.waitFor() != 0 || !Files.isRegularFile(cover)) return false;

            Process setIcon = new ProcessBuilder(
                    "/usr/bin/osascript", "-l", "JavaScript", "-e", SET_ICON_SCRIPT,
                    cover.toAbsolutePath().toString(), audioFile.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();
            setIcon.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return setIcon.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (tempDirectory != null) {
                try { Files.deleteIfExists(tempDirectory.resolve("cover.jpg")); } catch (Exception ignored) {}
                try { Files.deleteIfExists(tempDirectory); } catch (Exception ignored) {}
            }
        }
    }

    static boolean isSupportedAudio(Path file) {
        if (file == null || file.getFileName() == null) return false;
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                && AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
