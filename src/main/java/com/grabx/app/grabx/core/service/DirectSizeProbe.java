package com.grabx.app.grabx.core.service;

import java.net.HttpURLConnection;
import java.net.URL;

/** Bounded, header-only fallback for servers that omit the transfer length. */
final class DirectSizeProbe {
    private static final java.util.logging.Logger LOG =
            com.grabx.app.grabx.util.AppLog.get(DirectSizeProbe.class);
    private DirectSizeProbe() {}

    static java.util.concurrent.CompletableFuture<Long> probeAsync(URL url, String etag) {
        return probeAsync(url, etag, null);
    }

    static java.util.concurrent.CompletableFuture<Long> probeAsync(URL url, String etag, String referer) {
        var result = new java.util.concurrent.CompletableFuture<Long>();
        Thread worker = new Thread(() -> {
            try {
                long size = probe(url, etag, referer);
                if (size < 0) size = probe(url, etag, referer);
                result.complete(size);
                LOG.fine("File size discovery finished: " + size);
            }
            catch (Exception ignored) { result.complete(-1L); }
        }, "http-size-probe");
        worker.setDaemon(true);
        worker.start();
        // Per-request timeouts bound the work. Do not complete the future early:
        // doing so permanently discards a valid size that arrives later.
        return result;
    }

    static long probe(URL url, String etag) {
        return probe(url, etag, null);
    }

    static long probe(URL url, String etag, String referer) {
        for (String method : new String[]{"HEAD", "GET"}) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                com.grabx.app.grabx.util.RequestReferer.apply(connection, referer);
                if (method.equals("GET")) connection.setRequestProperty("Range", "bytes=0-0");
                int status = connection.getResponseCode();
                if (status != 200 && status != 206) continue;
                String responseEtag = connection.getHeaderField("ETag");
                if (etag != null && !etag.equals(responseEtag)) continue;
                long size = sizeFromHeaders(status, connection.getContentLengthLong(),
                        connection.getHeaderField("Content-Range"));
                if (size >= 0) return size;
            } catch (Exception error) {
                LOG.fine("File size " + method + " probe failed: " + error.getClass().getSimpleName());
                // Size discovery must never prevent a valid stream from downloading.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return -1;
    }

    static long sizeFromHeaders(int status, long length, String range) {
        if (status == 200) return length >= 0 ? length : -1;
        if (status != 206 || range == null) return -1;
        var match = java.util.regex.Pattern.compile("(?i)bytes\\s+(\\d+)-(\\d+)/(\\d+)").matcher(range.trim());
        if (!match.matches()) return -1;
        try {
            long start = Long.parseLong(match.group(1));
            long end = Long.parseLong(match.group(2));
            long total = Long.parseLong(match.group(3));
            return start <= end && end < total ? total : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
