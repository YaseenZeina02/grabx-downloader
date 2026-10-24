package com.grabx.app.grabx.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.HttpURLConnection;
import java.net.URL;

/** Exact source stream lengths, never bitrate/fragment estimates or final muxed size. */
public final class ExactMediaSize {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ExactMediaSize() { }

    public static Long selectedTotal(String metadata) {
        try {
            JsonNode root = JSON.readTree(metadata);
            JsonNode formats = root.path("requested_formats");
            if (!formats.isArray()) formats = JSON.createArrayNode().add(root);
            long total = 0;
            if (formats.isEmpty()) return null;
            for (JsonNode format : formats) {
                long size = size(format);
                if (size <= 0) return null;
                total = Math.addExact(total, size);
            }
            return total;
        } catch (Exception ignored) { return null; }
    }

    static String resolveStreams(String metadata) {
        try {
            JsonNode formats = JSON.readTree(metadata);
            if (!(formats instanceof ArrayNode)) return metadata;
            for (JsonNode format : formats) {
                if (format instanceof ObjectNode object && format.path("filesize").asLong(-1) <= 0) {
                    long size = size(format);
                    if (size > 0) object.put("filesize", size);
                }
            }
            return JSON.writeValueAsString(formats);
        } catch (Exception ignored) { return metadata; }
    }

    private static long size(JsonNode format) {
        long known = format.path("filesize").asLong(-1);
        if (known > 0) return known;
        String protocol = format.path("protocol").asText("");
        String address = format.path("url").asText("");
        if ((!protocol.isEmpty() && !protocol.equals("https") && !protocol.equals("http"))
                || format.hasNonNull("fragments") || format.path("is_live").asBoolean(false)) return -1;
        try {
            URL url = new URL(address);
            if (!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) return -1;
            if (url.getPath().matches("(?i).*\\.(m3u8|mpd)$")) return -1;
            long head = request(url, format.path("http_headers"), false);
            return head > 0 ? head : request(url, format.path("http_headers"), true);
        } catch (Exception ignored) { return -1; }
    }

    private static long request(URL url, JsonNode headers, boolean range) {
        HttpURLConnection connection = null;
        try {
            connection = com.grabx.app.grabx.util.VerifiedConnections.open(url);
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
            connection.setRequestMethod(range ? "GET" : "HEAD");
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (String header : new String[]{"User-Agent", "Referer"}) {
                String value = headers.path(header).asText("");
                if (!value.isBlank() && value.length() <= 4096 && value.chars().noneMatch(Character::isISOControl))
                    connection.setRequestProperty(header, value);
            }
            if (range) connection.setRequestProperty("Range", "bytes=0-0");
            int status = connection.getResponseCode();
            String encoding = connection.getHeaderField("Content-Encoding");
            String type = connection.getContentType();
            if ((encoding != null && !encoding.equalsIgnoreCase("identity"))
                    || (type != null && (type.contains("text/html") || type.contains("mpegurl") || type.contains("dash+xml")))) return -1;
            if (!range) return status == 200 ? connection.getContentLengthLong() : -1;
            if (status != 206) return -1;
            var match = java.util.regex.Pattern.compile("(?i)bytes 0-0/(\\d+)").matcher(
                    String.valueOf(connection.getHeaderField("Content-Range")));
            return match.matches() ? Long.parseLong(match.group(1)) : -1;
        } catch (Exception ignored) { return -1; }
        finally { if (connection != null) connection.disconnect(); }
    }
}
