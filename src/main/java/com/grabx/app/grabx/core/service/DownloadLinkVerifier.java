package com.grabx.app.grabx.core.service;

import java.io.*;
import java.net.*;
import java.nio.file.*;

/** A replacement is accepted only after all saved bytes match a stable remote version. */
public final class DownloadLinkVerifier {
    public record Verified(String etag, long total, int segments) { }
    public static Verified verify(String url, String referer, Path output, long previousTotal) throws Exception {
        return verify(url, referer, output, previousTotal, previousTotal > 0 ? DownloadRunner.directSegmentCount(previousTotal) : 0);
    }
    public static Verified verify(String url, String referer, Path output, long previousTotal, int segments) throws Exception {
        return verify(url, referer, output, previousTotal, segments, null);
    }
    public static Verified verify(String url, String referer, Path output, long previousTotal, int segments, String userAgent) throws Exception {
        URI uri = URI.create(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) throw new IOException("Enter a direct HTTP or HTTPS download URL");
        var remote = DirectRemoteProbe.probe(url, referer, userAgent, c -> {});
        long total = remote.total();
        String etag = remote.etag();
        if (!"Yes".equals(remote.support()) || total <= 0 || !DirectRemoteProbe.strong(etag))
            throw new DirectRemoteProbe.NeedsAction("Cannot safely resume: server must support ranges and a strong ETag");
        url = remote.finalUrl();
        if (previousTotal > 0 && previousTotal != total) throw new IOException("Different file size; saved parts kept");
        Path single = DirectPartialFiles.part(output, -1);
        if (!DirectDownloadState.hasSegments(output) && Files.isRegularFile(single)) compare(single, 0, total, url, referer, etag, userAgent);
        else {
            if (previousTotal <= 0) throw new IOException("Original segment layout is unavailable; saved parts kept");
            int count = segments;
            if (count <= 0 || count > 16) throw new IOException("Original segment layout is unavailable; saved parts kept");
            for (int i = 0; i < count; i++) {
                Path part = DirectPartialFiles.part(output, i);
                if (Files.isRegularFile(part)) compare(part, total * i / count, total, url, referer, etag, userAgent);
            }
        }
        return new Verified(etag, total, DirectDownloadState.hasSegments(output) ? segments : 0);
    }
    private static void compare(Path part, long offset, long total, String url, String referer, String etag, String userAgent) throws Exception {
        long length = Files.size(part);
        if (length == 0) return;
        if (length > total - offset) throw new IOException("Saved data exceeds new file size");
        HttpURLConnection c = DownloadRunner.openRangeConnection(url, offset, offset + length - 1, referer);
        c.setRequestProperty("If-Match", etag);
        if (userAgent != null && !userAgent.isBlank()) c.setRequestProperty("User-Agent", userAgent);
        try {
            if (c.getResponseCode() != 206 || !etag.equals(c.getHeaderField("ETag"))) throw new IOException("File changed during verification; saved parts kept");
            long end = SegmentResponse.validate(c.getHeaderField("Content-Range"), offset, offset + length - 1, total);
            if (end != offset + length - 1) throw new IOException("Server limited verification range; saved parts kept");
            try (InputStream local = Files.newInputStream(part); InputStream remote = c.getInputStream()) {
                byte[] bytes = new byte[65536];
                int n;
                while ((n = local.read(bytes)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    byte[] received = remote.readNBytes(n);
                    if (received.length != n || !java.util.Arrays.equals(bytes, 0, n, received, 0, n))
                        throw new IOException("New link contains different data; saved parts kept");
                }
            }
        } finally { c.disconnect(); }
    }
}
