package com.grabx.app.grabx.ui.probe;



import java.util.List;

public class ProbeAudioResult {

    private final List<AudioFormatInfo> formats;
    private final AudioFormatInfo best;

    public ProbeAudioResult(List<AudioFormatInfo> formats,
                            AudioFormatInfo best) {
        this.formats = formats;
        this.best = best;
    }

    public List<AudioFormatInfo> getFormats() {
        return formats;
    }

    public AudioFormatInfo getBest() {
        return best;
    }

    public boolean isEmpty() {
        return formats == null || formats.isEmpty();
    }
}