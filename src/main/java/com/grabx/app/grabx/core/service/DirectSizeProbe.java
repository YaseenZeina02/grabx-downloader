package com.grabx.app.grabx.core.service;

import java.net.HttpURLConnection;
import java.net.URL;

/** Bounded, header-only fallback for servers that omit the transfer length. */
final class DirectSizeProbe {
    private DirectSizeProbe() {}

    static java.util.concurrent.CompletableFuture<Long> probeAsync(URL url, String etag) {
        var result = new java.util.concurrent.CompletableFuture<Long>();
        Thread worker = new Thread(() -> {
            try { result.complete(probe(url, etag)); }
            catch (Exception ignored) { result.complete(-1L); }
        }, "http-size-probe");
        worker.setDaemon(true);
        worker.start();
        return result.completeOnTimeout(-1L, 7, java.util.concurrent.TimeUnit.SECONDS);
    }

    static long probe(URL url, String etag) {
        for (String method : new String[]{"HEAD", "GET"}) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(3_000);
                connection.setReadTimeout(3_000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (method.equals("GET")) connection.setRequestProperty("Range", "bytes=0-0");
                int status = connection.getResponseCode();
                if (status != 200 && status != 206) continue;
                String responseEtag = connection.getHeaderField("ETag");
                if (etag != null && !etag.equals(responseEtag)) continue;
                long size = sizeFromHeaders(status, connection.getContentLengthLong(),
                        connection.getHeaderField("Content-Range"));
                if (size >= 0) return size;
            } catch (Exception ignored) {
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
