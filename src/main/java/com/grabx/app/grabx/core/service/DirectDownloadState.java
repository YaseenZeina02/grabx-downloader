package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Properties;

/** Durable identity and immutable segment layout. Actual part lengths are the byte checkpoints. */
public final class DirectDownloadState {
    private DirectDownloadState() { }
    static Path path(Path output) { return output.resolveSibling("." + output.getFileName() + ".grabx.resume"); }

    public static void save(DownloadRow row, Path output) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1");
        put(p, "originalUrl", row.originalUrl); put(p, "url", row.url);
        put(p, "finalUrl", row.finalUrl); put(p, "referer", row.getReferer());
        put(p, "etag", row.resumeEtag); put(p, "lastModified", row.resumeLastModified);
        put(p, "support", row.resumeSupport);
        p.setProperty("total", Long.toString(row.resumeTotal));
        p.setProperty("segments", Integer.toString(row.resumeSegments));
        p.setProperty("output", output.toAbsolutePath().normalize().toString());
        put(p, "userAgent", row.requestUserAgent == null ? DirectRemoteProbe.USER_AGENT : row.requestUserAgent);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        p.store(bytes, "GrabX resume state; part sizes are authoritative");
        atomicWrite(path(output), bytes.toByteArray());
    }

    public static boolean load(DownloadRow row, Path output) throws IOException {
        Path file = path(output);
        if (!Files.exists(file)) return false;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        try {
            if (!"1".equals(p.getProperty("version")) || !output.toAbsolutePath().normalize().toString().equals(p.getProperty("output")))
                throw new IOException("Saved download metadata is invalid; parts kept");
            long total = Long.parseLong(p.getProperty("total"));
            int segments = Integer.parseInt(p.getProperty("segments"));
            if (total < -1 || segments < 0 || segments > 16 || (segments > 0 && total <= 0))
                throw new IOException("Saved download layout is invalid; parts kept");
            row.originalUrl = p.getProperty("originalUrl", row.originalUrl);
            row.url = p.getProperty("url", row.url); row.finalUrl = p.getProperty("finalUrl");
            row.setReferer(p.getProperty("referer"));
            row.requestUserAgent = p.getProperty("userAgent");
            row.resumeEtag = p.getProperty("etag"); row.resumeLastModified = p.getProperty("lastModified");
            row.resumeSupport = p.getProperty("support", "Unknown");
            row.resumeTotal = total; row.resumeSegments = segments;
            return true;
        } catch (IllegalArgumentException e) { throw new IOException("Cannot read saved download metadata; parts kept", e); }
    }

    static boolean hasSegments(Path output) {
        for (int i = 0; i < 16; i++) if (Files.isRegularFile(DirectPartialFiles.part(output, i))) return true;
        return false;
    }

    public static long downloaded(Path output) throws IOException {
        // During interrupted assembly, segment files remain authoritative.
        long sum = 0; boolean segmented = false;
        for (int i = 0; i < 16; i++) {
            Path part = DirectPartialFiles.part(output, i);
            if (Files.isRegularFile(part)) { segmented = true; sum = Math.addExact(sum, Files.size(part)); }
        }
        Path single = DirectPartialFiles.part(output, -1);
        return segmented ? sum : Files.isRegularFile(single) ? Files.size(single) : 0;
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".grabx-state-", ".tmp");
        try {
            Files.write(temp, bytes);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static void put(Properties p, String key, String value) { if (value != null) p.setProperty(key, value); }
}
