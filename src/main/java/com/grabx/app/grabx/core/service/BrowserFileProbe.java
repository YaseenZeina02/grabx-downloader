package com.grabx.app.grabx.core.service;

import java.net.URI;
import java.util.Locale;

/** Inspect a clicked link by its response, never by a guessed file extension or host name. */
public final class BrowserFileProbe {
    public record Result(boolean ok, boolean file, String url, String filename, String mimeType, String message) { }

    public static Result inspect(String url, String referer, String userAgent) {
        try {
            url = com.grabx.app.grabx.browser.BrowserBridgeProtocol.normalizeHttpUrl(url);
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            if (userAgent != null && (userAgent.length() > 1024 || userAgent.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException();
            var remote = DirectRemoteProbe.probe(url, referer, userAgent, c -> {});
            String mime = remote.contentType() == null ? "" : remote.contentType().split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            String disposition = remote.disposition() == null ? "" : remote.disposition();
            boolean document = mime.equals("text/html") || mime.equals("application/xhtml+xml");
            boolean attachment = disposition.toLowerCase(Locale.ROOT).startsWith("attachment");
            // JSON, XML, scripts and other text resources are also legitimate downloads.
            boolean file = !document && (attachment || !mime.isBlank());
            if (!file) return new Result(true, false, url, "", mime,
                    document ? "This link opens a web page. Use the site's download button to obtain a file link, then try again."
                            : "The server did not identify this file. Start the download with the site's download button.");
            String filename = DownloadRunner.selectDirectFilename(disposition, mime, "", remote.finalUrl());
            // Keep the original signed request; redirects will be checked again by the downloader.
            return new Result(true, true, url, filename, mime, "File link verified");
        } catch (DirectRemoteProbe.HttpFailure error) {
            return new Result(false, false, url, "", "", error.getMessage());
        } catch (com.grabx.app.grabx.browser.BrowserBridgeProtocol.ProtocolException | IllegalArgumentException error) {
            return new Result(false, false, "", "", "", "Invalid HTTP(S) download request");
        } catch (Exception error) {
            return new Result(false, false, url, "", "", "Could not verify this download link. The browser download has not been cancelled.");
        }
    }
}
