package com.grabx.app.grabx.ui.probe;


public class VideoFormatInfo {

    private final String formatId;
    private final int height;        // 1080, 720
    private final int fps;
    private final String extension;  // mp4, webm
    private final long sizeBytes;

    public VideoFormatInfo(String formatId,
                           int height,
                           int fps,
                           String extension,
                           long sizeBytes) {
        this.formatId = formatId;
        this.height = height;
        this.fps = fps;
        this.extension = extension;
        this.sizeBytes = sizeBytes;
    }

    public String getFormatId() {
        return formatId;
    }

    public int getHeight() {
        return height;
    }

    public int getFps() {
        return fps;
    }

    public String getExtension() {
        return extension;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String displayLabel() {
        return height + "p" + (fps > 0 ? " " + fps + "fps" : "");
    }
}