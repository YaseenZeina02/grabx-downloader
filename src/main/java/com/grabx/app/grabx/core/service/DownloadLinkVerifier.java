package com.grabx.app.grabx.core.service;

import java.io.*;
import java.net.*;
import java.nio.file.*;

/** A replacement is accepted only after all saved bytes match a stable remote version. */
public final class DownloadLinkVerifier {
    public record Verified(String etag, long total) { }
    public static Verified verify(String url, String referer, Path output, long previousTotal) throws Exception {
        URI uri = URI.create(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) throw new IOException("Enter a direct HTTP or HTTPS download URL");
        HttpURLConnection probe = DownloadRunner.openRangeConnection(url, 0, 0, referer);
        String etag;
        long total;
        try {
            int status = probe.getResponseCode();
            total = DirectSizeProbe.sizeFromHeaders(status, probe.getContentLengthLong(), probe.getHeaderField("Content-Range"));
            etag = probe.getHeaderField("ETag");
            if (status != 206 || total <= 0 || etag == null || etag.startsWith("W/") || !etag.startsWith("\""))
                throw new IOException("Cannot safely resume: server must support ranges and a strong ETag; saved parts kept");
        } finally { probe.disconnect(); }
        if (previousTotal > 0 && previousTotal != total) throw new IOException("Different file size; saved parts kept");
        Path single = DirectPartialFiles.part(output, -1);
        if (Files.isRegularFile(single)) compare(single, 0, total, url, referer, etag);
        else {
            if (previousTotal <= 0) throw new IOException("Original segment layout is unavailable; saved parts kept");
            int count = DownloadRunner.directSegmentCount(previousTotal);
            for (int i = 0; i < count; i++) {
                Path part = DirectPartialFiles.part(output, i);
                if (Files.isRegularFile(part)) compare(part, total * i / count, total, url, referer, etag);
            }
        }
        return new Verified(etag, total);
    }
    private static void compare(Path part, long offset, long total, String url, String referer, String etag) throws Exception {
        long length = Files.size(part);
        if (length == 0) return;
        if (length > total - offset) throw new IOException("Saved data exceeds new file size");
        HttpURLConnection c = DownloadRunner.openRangeConnection(url, offset, offset + length - 1, referer);
        c.setRequestProperty("If-Match", etag);
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
