package com.grabx.app.grabx.ui.probe;

import java.util.List;

public class ProbeVideoResult {

    private final List<VideoFormatInfo> formats;
    private final VideoFormatInfo best;

    public ProbeVideoResult(List<VideoFormatInfo> formats,
                            VideoFormatInfo best) {
        this.formats = formats;
        this.best = best;
    }

    public List<VideoFormatInfo> getFormats() {
        return formats;
    }

    public VideoFormatInfo getBest() {
        return best;
    }

    public boolean isEmpty() {
        return formats == null || formats.isEmpty();
    }
}