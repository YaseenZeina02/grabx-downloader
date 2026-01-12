package com.grabx.app.grabx.core.model.probe;
import java.util.List;

public class PlaylistProbeService {

    public List<String> probeVideoIds(String playlistUrl) {

        // ⚠️ لاحقًا: yt-dlp --flat-playlist -J
        return List.of(
                "videoId1",
                "videoId2",
                "videoId3"
        );
    }
}