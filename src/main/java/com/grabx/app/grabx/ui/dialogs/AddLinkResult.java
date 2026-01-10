package com.grabx.app.grabx.ui.dialogs;

public class AddLinkResult {
    private String url;
    private boolean audioOnly;
    private String quality;
    private String folder;

    public AddLinkResult() {}

    public AddLinkResult(String url, boolean audioOnly, String quality, String folder) {
        this.url = url;
        this.audioOnly = audioOnly;
        this.quality = quality;
        this.folder = folder;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public boolean isAudioOnly() { return audioOnly; }
    public void setAudioOnly(boolean audioOnly) { this.audioOnly = audioOnly; }

    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
}