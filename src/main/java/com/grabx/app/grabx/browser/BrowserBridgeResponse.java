package com.grabx.app.grabx.browser;

/** Small response kept well below the browser native-messaging response limit. */
public record BrowserBridgeResponse(
        boolean ok,
        String requestId,
        String status,
        String message
) {
    public static BrowserBridgeResponse accepted(String requestId, boolean appRunning) {
        return new BrowserBridgeResponse(true, requestId,
                appRunning ? "delivered" : "queued",
                appRunning ? "Sent to GrabX" : "Queued — open GrabX to continue");
    }

    public static BrowserBridgeResponse rejected(String requestId, String message) {
        return new BrowserBridgeResponse(false, requestId, "rejected", message);
    }
}
