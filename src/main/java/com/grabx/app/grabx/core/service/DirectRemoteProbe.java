package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import java.io.*;
import java.net.*;
import java.util.function.Consumer;

/** Small GET probe and response validation shared by recovery and link refresh. */
final class DirectRemoteProbe {
    static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/121 Safari/537.36";
    record Remote(String finalUrl, long total, String etag, String modified, String support, String disposition, String contentType) { }
    static class NeedsAction extends IOException { NeedsAction(String message) { super(message + " • saved data kept"); } }
    static final class HttpFailure extends NeedsAction {
        final int status;
        HttpFailure(int status) {
            super(switch (status) {
                case 401 -> "Authentication/session required";
                case 498 -> "Download link/session rejected (HTTP 498); get a fresh link in the browser";
                case 403, 404, 410 -> "Download link expired or unavailable; update the link";
                case 412 -> "Remote file changed; verify a replacement link";
                case 416 -> "Range not satisfiable; verify the remote file";
                default -> "HTTP " + status;
            });
            this.status = status;
        }
    }
    static void checkStatus(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        if (DownloadRunner.isTemporaryHttpFailure(status))
            throw new DownloadRunner.SegmentServerBusy(status, c.getHeaderField("Retry-After"));
        if (status != 200 && status != 206) throw new HttpFailure(status);
        String encoding = c.getHeaderField("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity"))
            throw new NeedsAction("Server sent encoded data; byte ranges cannot be verified");
    }
    static Remote probe(String url, String referer, Consumer<HttpURLConnection> opened) throws Exception {
        return probe(url, referer, null, opened);
    }
    static Remote probe(String url, String referer, String userAgent, Consumer<HttpURLConnection> opened) throws Exception {
        HttpURLConnection c = null;
        for (int redirects = 0; ; redirects++) {
            c = DownloadRunner.openRangeConnection(url, 0, 0, referer);
            if (userAgent != null && !userAgent.isBlank()) c.setRequestProperty("User-Agent", userAgent);
            c.setInstanceFollowRedirects(false);
            opened.accept(c);
            int status;
            try { status = c.getResponseCode(); }
            catch (Exception e) { c.disconnect(); throw e; }
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) break;
            String location = c.getHeaderField("Location");
            URL previous = c.getURL();
            c.disconnect();
            if (redirects >= 10 || location == null) throw new NeedsAction("Invalid or excessive download redirects");
            URL next = new URL(previous, location);
            if (!(next.getProtocol().equals("http") || next.getProtocol().equals("https")) || next.getUserInfo() != null)
                throw new NeedsAction("Unsupported download redirect");
            url = next.toString();
        }
        try {
            int status = c.getResponseCode();
            // Empty resources have no satisfiable byte range.
            if (status == 416 && "bytes */0".equals(c.getHeaderField("Content-Range")))
                return new Remote(c.getURL().toString(), 0, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), "Yes", c.getHeaderField("Content-Disposition"), c.getContentType());
            checkStatus(c);
            long total = DirectSizeProbe.sizeFromHeaders(status, c.getContentLengthLong(), c.getHeaderField("Content-Range"));
            if (status == 206) {
                SegmentResponse.validate(c.getHeaderField("Content-Range"), 0, 0, total);
                if (c.getContentLengthLong() > 1) throw new NeedsAction("Invalid range probe length");
                try (InputStream in = c.getInputStream()) {
                    if (in.read() < 0) throw new EOFException("Range probe interrupted");
                }
            }
            return new Remote(c.getURL().toString(), total, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), status == 206 ? "Yes" : "No", c.getHeaderField("Content-Disposition"), c.getContentType());
        } finally { c.disconnect(); }
    }
    static boolean strong(String etag) { return etag != null && etag.startsWith("\"") && etag.endsWith("\""); }
    static String validator(DownloadRow row) {
        return strong(row.resumeEtag) ? row.resumeEtag : row.resumeLastModified;
    }
    static void verify(DownloadRow row, Remote remote) throws NeedsAction {
        if (!"Yes".equals(remote.support())) throw new NeedsAction("Server does not support resume");
        if (row.resumeTotal >= 0 && row.resumeTotal != remote.total()) throw new NeedsAction("Remote file size changed");
        if (strong(row.resumeEtag)) {
            if (!row.resumeEtag.equals(remote.etag())) throw new NeedsAction("Remote file version changed");
        } else if (row.resumeLastModified == null || !row.resumeLastModified.equals(remote.modified()) || row.resumeTotal < 0) {
            throw new NeedsAction("Cannot verify remote identity; provide a verifiable replacement link");
        }
    }
    static void conditional(HttpURLConnection c, DownloadRow row, boolean range) {
        String validator = validator(row);
        if (validator != null && range) c.setRequestProperty("If-Range", validator);
        else if (strong(row.resumeEtag)) c.setRequestProperty("If-Match", row.resumeEtag);
    }
    static void validateIdentity(HttpURLConnection c, DownloadRow row) throws IOException {
        checkStatus(c);
        if (strong(row.resumeEtag) && !row.resumeEtag.equals(c.getHeaderField("ETag")))
            throw new NeedsAction("Remote file version changed");
        if (!strong(row.resumeEtag) && row.resumeLastModified != null
                && !row.resumeLastModified.equals(c.getHeaderField("Last-Modified")))
            throw new NeedsAction("Remote file modification time changed");
    }
}
