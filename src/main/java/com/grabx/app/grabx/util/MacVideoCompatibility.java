package com.grabx.app.grabx.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Keeps downloaded video playable by macOS Quick Look without reducing resolution. */
public final class MacVideoCompatibility {
    private MacVideoCompatibility() {}

    record Codecs(String video, String audio, boolean tenBit) {
        boolean copyVideo() { return (video.equals("h264") && !tenBit) || video.equals("hevc"); }
        boolean copyAudio() { return audio.isEmpty() || audio.equals("aac") || audio.equals("alac"); }
    }

    static Codecs codecs(String metadata) throws IOException {
        String video = "", audio = "";
        boolean tenBit = false;
        var pattern = Pattern.compile("Stream #.*?: (Video|Audio): ([a-zA-Z0-9_]+)");
        for (String line : metadata.split("\\R")) {
            var match = pattern.matcher(line);
            if (!match.find() || line.contains("attached pic")) continue;
            if (match.group(1).equals("Video") && video.isEmpty()) {
                video = match.group(2);
                tenBit = line.contains("yuv420p10") || line.contains("High 10")
                        || line.contains("yuv422") || line.contains("yuv444");
            } else if (match.group(1).equals("Audio") && audio.isEmpty()) audio = match.group(2);
        }
        if (video.isEmpty()) throw new IOException("Could not inspect downloaded video");
        return new Codecs(video, audio, tenBit);
    }

    /** Original file survives failures and cancellation. Only completed conversion replaces it. */
    public static Path prepare(Path input, Path ffmpeg, Consumer<Process> track,
                               BooleanSupplier stopped, Runnable preparing) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                || input == null || !Files.isRegularFile(input)) return input;
        if (ffmpeg == null) throw new IOException("FFmpeg is required for macOS video playback");
        if (stopped.getAsBoolean()) return input;
        Process probe = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", input.toString())
                .redirectErrorStream(true).start();
        track.accept(probe);
        String metadata;
        try {
            metadata = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            probe.waitFor(); // ffmpeg -i reports metadata and exits 1 without an output.
        } catch (IOException error) {
            if (stopped.getAsBoolean()) return input;
            throw error;
        } finally { if (probe.isAlive()) probe.destroyForcibly(); }
        if (stopped.getAsBoolean()) return input;
        Codecs codecs = codecs(metadata);
        boolean mp4 = input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4");
        if (mp4 && codecs.copyVideo() && codecs.copyAudio()) return input;
        preparing.run();
        Path temporary = Files.createTempFile(input.getParent(), ".grabx-mac-", ".mp4");
        Process conversion = null;
        try {
            var args = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-y", "-i", input.toString(),
                    "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0", "-c:v", codecs.copyVideo() ? "copy" : "libx264"));
            if (!codecs.copyVideo()) args.addAll(List.of("-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p"));
            if (codecs.video().equals("hevc")) args.addAll(List.of("-tag:v", "hvc1"));
            args.addAll(List.of("-c:a", codecs.copyAudio() ? "copy" : "aac"));
            if (!codecs.copyAudio()) args.addAll(List.of("-b:a", "192k"));
            args.addAll(List.of("-movflags", "+faststart", temporary.toString()));
            if (stopped.getAsBoolean()) return input;
            conversion = new ProcessBuilder(args).redirectErrorStream(true).start();
            track.accept(conversion);
            if (stopped.getAsBoolean()) conversion.destroyForcibly();
            String lastError = "";
            try (var reader = new BufferedReader(new InputStreamReader(conversion.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) if (!line.isBlank()) lastError = line;
            }
            int code = conversion.waitFor();
            if (stopped.getAsBoolean()) return input;
            if (code != 0 || Files.size(temporary) == 0) throw new IOException("macOS video preparation failed: " + lastError);
            if (mp4) {
                Files.move(temporary, input, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return input;
            }
            String name = input.getFileName().toString();
            String stem = name.substring(0, name.lastIndexOf('.') > 0 ? name.lastIndexOf('.') : name.length());
            Path output = input.resolveSibling(stem + ".mp4");
            for (int i = 1; Files.exists(output); i++) output = input.resolveSibling(stem + " (" + i + ").mp4");
            Files.move(temporary, output);
            Files.delete(input);
            return output;
        } catch (IOException error) {
            if (stopped.getAsBoolean()) return input;
            throw error;
        } finally {
            if (conversion != null && conversion.isAlive()) conversion.destroyForcibly();
            Files.deleteIfExists(temporary);
        }
    }
}
