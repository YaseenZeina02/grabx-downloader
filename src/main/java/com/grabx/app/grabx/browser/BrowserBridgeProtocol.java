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
        return new BrowserCapture(
                capture.protocolVersion(), "capture", capture.requestId(), pageUrl, mediaUrl,
                limit(capture.title(), 500), limit(capture.mimeType(), 160), kind, action,
                capture.createdAt() > 0 ? capture.createdAt() : System.currentTimeMillis()
        );
    }

    private static String requireHttpUrl(String value, String label) throws ProtocolException {
        String normalized = optionalHttpUrl(value, label);
        if (normalized == null) throw new ProtocolException("Missing " + label);
        return normalized;
    }

    private static String optionalHttpUrl(String value, String label) throws ProtocolException {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new ProtocolException("Invalid " + label);
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid " + label);
        }
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
