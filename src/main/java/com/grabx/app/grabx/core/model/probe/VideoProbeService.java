package com.grabx.app.grabx.core.model.probe;

import com.grabx.app.grabx.ui.probe.*;
import java.util.ArrayList;
import java.util.List;

public class VideoProbeService {

    public ProbeVideoResult probe(String url) {

        // ⚠️ Mock — لاحقًا yt-dlp -J
        List<VideoFormatInfo> list = new ArrayList<>();

        list.add(new VideoFormatInfo("137", 1080, 60, "mp4", -1));
        list.add(new VideoFormatInfo("136", 720, 30, "mp4", -1));
        list.add(new VideoFormatInfo("135", 480, 30, "mp4", -1));

        VideoFormatInfo best = list.stream()
                .max((a, b) -> Integer.compare(a.getHeight(), b.getHeight()))
                .orElse(null);

        return new ProbeVideoResult(list, best);
    }
}