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
}
