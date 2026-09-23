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
        String suggestedFilename,
        String suggestedFolder,
        boolean browserDestinationResolved,
        long createdAt,
        java.util.List<Integer> availableQualities,
        java.util.List<BrowserVideoSize> qualitySizes
) {
    public BrowserCapture {
        availableQualities = availableQualities == null ? java.util.List.of() : java.util.List.copyOf(availableQualities);
        qualitySizes = qualitySizes == null ? java.util.List.of() : java.util.List.copyOf(qualitySizes);
    }
    public BrowserCapture(int protocolVersion, String type, String requestId, String pageUrl, String mediaUrl,
                          String title, String mimeType, String mediaKind, String action, String suggestedFilename,
                          String suggestedFolder, boolean browserDestinationResolved, long createdAt,
                          java.util.List<Integer> availableQualities) {
        this(protocolVersion, type, requestId, pageUrl, mediaUrl, title, mimeType, mediaKind, action,
                suggestedFilename, suggestedFolder, browserDestinationResolved, createdAt, availableQualities, java.util.List.of());
    }
    public BrowserCapture(int protocolVersion, String type, String requestId, String pageUrl, String mediaUrl,
                          String title, String mimeType, String mediaKind, String action, String suggestedFilename,
                          String suggestedFolder, boolean browserDestinationResolved, long createdAt) {
        this(protocolVersion, type, requestId, pageUrl, mediaUrl, title, mimeType, mediaKind, action,
                suggestedFilename, suggestedFolder, browserDestinationResolved, createdAt, java.util.List.of());
    }
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public String effectiveUrl() {
        return mediaUrl != null && !mediaUrl.isBlank() ? mediaUrl : pageUrl;
    }

    /** Only a resolved browser destination can bypass the application's chooser. */
    public java.nio.file.Path browserDestinationDirectory() {
        if (!browserDestinationResolved || !"file".equals(action) || suggestedFolder == null) return null;
        try {
            var path = java.nio.file.Path.of(suggestedFolder);
            return path.isAbsolute() && java.nio.file.Files.isDirectory(path)
                    && java.nio.file.Files.isWritable(path) ? path : null;
        } catch (java.nio.file.InvalidPathException exception) {
            return null;
        }
    }
}
