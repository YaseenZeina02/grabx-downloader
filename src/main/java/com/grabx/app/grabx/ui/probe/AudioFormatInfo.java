package com.grabx.app.grabx.ui.probe;


public class AudioFormatInfo {

    private final String formatId;   // yt-dlp format id
    private final String extension;  // mp3, m4a, webm
    private final int bitrateKbps;   // 128, 160, 320
    private final String codec;      // mp3, opus, aac
    private final long sizeBytes;    // may be -1 if unknown

    public AudioFormatInfo(String formatId,
                           String extension,
                           int bitrateKbps,
                           String codec,
                           long sizeBytes) {
        this.formatId = formatId;
        this.extension = extension;
        this.bitrateKbps = bitrateKbps;
        this.codec = codec;
        this.sizeBytes = sizeBytes;
    }

    public String getFormatId() {
        return formatId;
    }

    public String getExtension() {
        return extension;
    }

    public int getBitrateKbps() {
        return bitrateKbps;
    }

    public String getCodec() {
        return codec;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    /** نص جاهز للـ ComboBox */
    public String displayLabel() {
        String br = bitrateKbps > 0 ? bitrateKbps + " kbps" : "Unknown bitrate";
        return extension.toUpperCase() + " • " + br;
    }
}