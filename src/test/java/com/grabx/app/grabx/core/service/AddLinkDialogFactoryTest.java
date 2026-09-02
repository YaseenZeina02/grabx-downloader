package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AddLinkDialogFactoryTest {
    @Test
    void createsTheSharedDialogConfiguration() {
        AddLinkDialogService.Config config = AddLinkDialogFactory.defaultConfig(
                "Video", "Audio only", "Best", "---", "mp3", List.of("mp3", "m4a")
        );

        assertEquals("Video", config.MODE_VIDEO);
        assertEquals("Audio only", config.MODE_AUDIO);
        assertEquals("Best", config.QUALITY_BEST);
        assertEquals(List.of("mp3", "m4a"), config.AUDIO_FORMATS);
        assertEquals("/com/grabx/app/grabx/styles/theme-base.css", config.THEME_BASE_CSS);
        assertEquals("/com/grabx/app/grabx/styles/sidebar.css", config.SIDEBAR_CSS);
    }
}
