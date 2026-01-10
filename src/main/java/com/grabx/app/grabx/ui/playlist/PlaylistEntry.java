package com.grabx.app.grabx.ui.playlist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaylistEntry {

    private final int index; // 1-based position in playlist
    private final String id;
    private final String title;
    private final String thumbUrl;

    private boolean selected;

    // unavailable items (private/deleted/etc.)
    private boolean unavailable;
    private String unavailableReason;

    // UI state
    private boolean qualitiesLoaded;
    private boolean manualQuality;

    private String quality = "Best quality (Recommended)";
    private Map<String, String> sizeByQuality = new HashMap<>();
    private List<String> availableQualities = new ArrayList<>();

    public PlaylistEntry(int index, String id, String title, String thumbUrl, boolean selected) {
        this.index = index;
        this.id = id;
        this.title = title;
        this.thumbUrl = thumbUrl;
        this.selected = selected;
    }

    public int getIndex() { return index; }
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getThumbUrl() { return thumbUrl; }

    public boolean isUnavailable() { return unavailable; }
    public void setUnavailable(boolean unavailable) { this.unavailable = unavailable; }

    public String getUnavailableReason() { return unavailableReason; }
    public void setUnavailableReason(String unavailableReason) { this.unavailableReason = unavailableReason; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public String displayTitle() {
        String base = (title == null || title.isBlank()) ? id : title;
        // Prefix with number for playlists: "1. Title"
        return index > 0 ? (index + ". " + base) : base;
    }

    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }

    public boolean isQualitiesLoaded() { return qualitiesLoaded; }
    public void setQualitiesLoaded(boolean qualitiesLoaded) { this.qualitiesLoaded = qualitiesLoaded; }

    public boolean isManualQuality() { return manualQuality; }
    public void setManualQuality(boolean manualQuality) { this.manualQuality = manualQuality; }

    public void setSizeByQuality(Map<String, String> map) {
        this.sizeByQuality = (map == null) ? new HashMap<>() : map;
    }

    public String getSizeForQuality(String qualityLabel) {
        if (qualityLabel == null) return null;
        return sizeByQuality.get(qualityLabel);
    }

    public List<String> getAvailableQualities() { return availableQualities; }

    public void setAvailableQualities(List<String> qualities) {
        this.availableQualities = (qualities == null) ? new ArrayList<>() : qualities;
    }
}