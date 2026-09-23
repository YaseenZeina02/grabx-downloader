package com.grabx.app.grabx.browser;

/** Estimated transfer bytes (video plus audio), not the final merged file size. */
public record BrowserVideoSize(int quality, long bytes) {}
