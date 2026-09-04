package com.grabx.app.grabx.browser;

/** Versioned media/file capture sent by the GrabX browser extension. */
public record BrowserCapture(
        int protocolVersion,
        String type,
        String requestId,
        String pageUrl,
        String mediaUrl,
        String title,
        String mimeType,
        String mediaKind,
        String action,
        long createdAt
) {
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public String effectiveUrl() {
        return mediaUrl != null && !mediaUrl.isBlank() ? mediaUrl : pageUrl;
    }
}
