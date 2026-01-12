package com.grabx.app.grabx.core.model.probe;

import com.grabx.app.grabx.ui.probe.*;

import java.util.ArrayList;
import java.util.List;

public class AudioProbeService {

    public ProbeAudioResult probe(String url) {

        // ⚠️ مؤقتًا Mock — لاحقًا نربطه yt-dlp -J
        List<AudioFormatInfo> list = new ArrayList<>();

        list.add(new AudioFormatInfo("251", "webm", 160, "opus", -1));
        list.add(new AudioFormatInfo("140", "m4a", 128, "aac", -1));
        list.add(new AudioFormatInfo("mp3-320", "mp3", 320, "mp3", -1));

        AudioFormatInfo best = list.stream()
                .max((a, b) -> Integer.compare(a.getBitrateKbps(), b.getBitrateKbps()))
                .orElse(null);

        return new ProbeAudioResult(list, best);
    }
}