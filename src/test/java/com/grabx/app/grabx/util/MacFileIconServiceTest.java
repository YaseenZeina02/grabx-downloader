package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacFileIconServiceTest {
    @Test
    void recognizesSupportedAudioFilesCaseInsensitively() {
        assertTrue(MacFileIconService.isSupportedAudio(Path.of("song.MP3")));
        assertTrue(MacFileIconService.isSupportedAudio(Path.of("song.m4a")));
        assertFalse(MacFileIconService.isSupportedAudio(Path.of("video.mp4")));
        assertFalse(MacFileIconService.isSupportedAudio(null));
    }
}
