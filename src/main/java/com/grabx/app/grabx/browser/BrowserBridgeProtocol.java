package com.grabx.app.grabx.browser;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses and validates the untrusted boundary between a web page and GrabX. */
public final class BrowserBridgeProtocol {
    public static final int MAX_MESSAGE_BYTES = 1_048_576;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{8,100}");
    private static final Set<String> KINDS = Set.of("page", "video", "audio", "file");
    private static final Set<String> ACTIONS = Set.of("ask", "video", "audio", "file");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public BrowserCapture parse(byte[] json) throws ProtocolException {
        if (json == null || json.length == 0) throw new ProtocolException("Empty request");
        if (json.length > MAX_MESSAGE_BYTES) throw new ProtocolException("Request is too large");
        try {
            return validate(mapper.readValue(json, BrowserCapture.class));
        } catch (ProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProtocolException("Invalid request format");
        }
    }

    public byte[] serialize(Object value) throws ProtocolException {
        try {
            return mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new ProtocolException("Could not encode response");
        }
    }

    public BrowserCapture validate(BrowserCapture capture) throws ProtocolException {
        if (capture == null) throw new ProtocolException("Missing request");
        if (capture.protocolVersion() != BrowserCapture.CURRENT_PROTOCOL_VERSION) {
            throw new ProtocolException("Unsupported protocol version");
        }
        if (!"capture".equals(capture.type())) throw new ProtocolException("Unsupported request type");
        if (capture.requestId() == null || !REQUEST_ID.matcher(capture.requestId()).matches()) {
            throw new ProtocolException("Invalid request ID");
        }
        String pageUrl = requireHttpUrl(capture.pageUrl(), "page URL");
        String mediaUrl = optionalHttpUrl(capture.mediaUrl(), "media URL");
        String kind = normalizeAllowed(capture.mediaKind(), KINDS, "media kind", "page");
        String action = normalizeAllowed(capture.action(), ACTIONS, "action", "ask");
        var qualities = validatedQualities(capture, pageUrl, mediaUrl, action);
        var sizes = new java.util.LinkedHashMap<Integer, BrowserVideoSize>();
        if (capture.qualitySizes().size() <= 16) {
            for (var size : capture.qualitySizes()) {
                if (qualities.contains(size.quality()) && size.bytes() > 0 && size.bytes() <= 10_000_000_000_000L) {
                    sizes.putIfAbsent(size.quality(), size);
                }
            }
        }
        return new BrowserCapture(
                capture.protocolVersion(), "capture", capture.requestId(), pageUrl, mediaUrl,
                limit(capture.title(), 500), limit(capture.mimeType(), 160), kind, action,
                limit(capture.suggestedFilename(), 255), limit(capture.suggestedFolder(), 4096),
                capture.browserDestinationResolved(),
                capture.createdAt() > 0 ? capture.createdAt() : System.currentTimeMillis(),
                qualities, java.util.List.copyOf(sizes.values())
        );
    }

    private static java.util.List<Integer> validatedQualities(BrowserCapture capture, String pageUrl, String mediaUrl, String action) {
        String host = URI.create(pageUrl).getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be"))
                || capture.availableQualities().size() > 16 || !Set.of("video", "audio", "ask").contains(action)
                || capture.createdAt() > System.currentTimeMillis() + 30_000
                || System.currentTimeMillis() - capture.createdAt() > 300_000) return java.util.List.of();
        String video = com.grabx.app.grabx.util.YouTubeUrls.extractVideoId(pageUrl);
        if (video == null || !video.matches("[A-Za-z0-9_-]{11}") || (mediaUrl != null
                && !com.grabx.app.grabx.util.YouTubeUrls.normalizeSingleVideoUrl(pageUrl)
                .equals(com.grabx.app.grabx.util.YouTubeUrls.normalizeSingleVideoUrl(mediaUrl)))) return java.util.List.of();
        return capture.availableQualities().stream()
                .filter(h -> h != null && h > 0 && com.grabx.app.grabx.util.VideoQualityUtils.normalizeHeight(h) == h)
                .distinct().sorted(java.util.Comparator.reverseOrder()).toList();
    }

    private static String requireHttpUrl(String value, String label) throws ProtocolException {
        String normalized = optionalHttpUrl(value, label);
        if (normalized == null) throw new ProtocolException("Missing " + label);
        return normalized;
    }

    private static String optionalHttpUrl(String value, String label) throws ProtocolException {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(encodeBrowserUrl(value.trim()));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new ProtocolException("Invalid " + label + ": expected an HTTP(S) address with a valid host");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid " + label + ": unsupported address syntax");
        }
    }

    // WHATWG URL (the browser) allows characters that java.net.URI rejects.
    // Quote only those characters, preserving existing escapes and signed query parameters.
    private static String encodeBrowserUrl(String value) {
        int authorityStart = value.indexOf("://");
        if (authorityStart < 0) throw new IllegalArgumentException("Missing authority");
        int authorityEnd = authorityStart + 3;
        while (authorityEnd < value.length() && "/?#".indexOf(value.charAt(authorityEnd)) < 0) authorityEnd++;
        StringBuilder result = new StringBuilder();
        boolean queryOrFragment = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) throw new IllegalArgumentException("Control character");
            if (ch == '?' || ch == '#') queryOrFragment = true;
            boolean invalidEscape = ch == '%' && (i + 2 >= value.length()
                    || Character.digit(value.charAt(i + 1), 16) < 0
                    || Character.digit(value.charAt(i + 2), 16) < 0);
            boolean quote = i >= authorityEnd && (" \"<>\\^`{|}".indexOf(ch) >= 0 || invalidEscape
                    || (!queryOrFragment && (ch == '[' || ch == ']')));
            if (quote) result.append('%').append("0123456789ABCDEF".charAt(ch >> 4))
                    .append("0123456789ABCDEF".charAt(ch & 15));
            else result.append(ch);
        }
        return result.toString();
    }

    private static String normalizeAllowed(String value, Set<String> allowed, String label, String fallback)
            throws ProtocolException {
        String normalized = value == null || value.isBlank() ? fallback : value.toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new ProtocolException("Invalid " + label);
        return normalized;
    }

    private static String limit(String value, int maxLength) {
        if (value == null) return "";
        String stripped = value.strip().replace('\0', ' ');
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
