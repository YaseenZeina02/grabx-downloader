package com.grabx.app.grabx.core.model.probe;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoProbeServiceTest {
    private final VideoProbeService service = new VideoProbeService();

    @Test
    void parsesAndNormalizesUniqueHeights() {
        String json = """
                {"formats":[
                  {"height":1076},
                  {"height":1080},
                  {"height":718},
                  {"height":50},
                  {"format_id":"audio-only"}
                ]}
                """;

        assertEquals(Set.of(720, 1080), service.parseHeights(json));
    }

    @Test
    void toleratesLeadingToolOutputAndInvalidJson() {
        assertEquals(Set.of(2160), service.parseHeights("warning line\n{\"formats\":[{\"height\":2160}]}"));
        assertEquals(Set.of(), service.parseHeights("not json"));
    }
    @Test
    void portraitQualitiesUseLabelsOrShortSideAndIgnoreStoryboards() {
        assertEquals(Set.of(144, 240, 360, 480, 720, 1080), service.parseHeights("""
                {"formats":[
                  {"width":144,"height":192,"vcodec":"avc1"},
                  {"width":240,"height":320,"vcodec":"avc1"},
                  {"width":360,"height":480,"vcodec":"avc1"},
                  {"width":480,"height":640,"format_note":"480p","vcodec":"avc1"},
                  {"width":720,"height":960,"format_note":"720p60","vcodec":"av01"},
                  {"width":1080,"height":1440,"format_note":"1080p","vcodec":"avc1"},
                  {"width":1920,"height":1080,"format_note":"1080p Premium","vcodec":"vp9"},
                  {"height":2160,"vcodec":"none"},
                  {"height":4320,"has_drm":true,"vcodec":"av01"}
                ]}
                """));
    }
}
