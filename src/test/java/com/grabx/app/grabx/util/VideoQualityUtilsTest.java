package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoQualityUtilsTest {
    private static final String BEST = "Best quality (Recommended)";
    private static final String SEPARATOR = "──────────────";

    @Test
    void parsesHeightFromCommonLabels() {
        assertEquals(1080, VideoQualityUtils.parseHeight("1080p60"));
        assertEquals(2160, VideoQualityUtils.parseHeight("2160p (4K)"));
        assertEquals(-1, VideoQualityUtils.parseHeight(BEST));
    }

    @Test
    void formatsNamedResolutionsWithoutMislabeling8k() {
        assertEquals("4320p (8K)", VideoQualityUtils.formatHeightLabel(4320));
        assertEquals("2160p (4K)", VideoQualityUtils.formatHeightLabel(2160));
        assertEquals("1440p (2K)", VideoQualityUtils.formatHeightLabel(1440));
        assertEquals("2880p", VideoQualityUtils.formatHeightLabel(2880));
    }

    @Test
    void normalizesSmallEncoderVariationsUsingRelativeTolerance() {
        assertEquals(1080, VideoQualityUtils.normalizeHeight(1076));
        assertEquals(1440, VideoQualityUtils.normalizeHeight(1434));
        assertEquals(4320, VideoQualityUtils.normalizeHeight(4300));
        assertEquals(-1, VideoQualityUtils.normalizeHeight(120));
        assertEquals(Set.of(720, 1080), VideoQualityUtils.normalizeHeights(Set.of(718, 1076, 50)));
    }

    @Test
    void choosesClosestQualityWithoutJumpingAboveToTheLargest() {
        assertEquals("720p", VideoQualityUtils.closestSupportedLabel(
                "1080p", List.of(BEST, SEPARATOR, "1440p (2K)", "720p"), BEST, SEPARATOR));
        assertEquals("1080p", VideoQualityUtils.closestSupportedLabel(
                "144p", List.of(BEST, SEPARATOR, "2160p (4K)", "1080p"), BEST, SEPARATOR));
        assertEquals(BEST, VideoQualityUtils.closestSupportedLabel(
                BEST, List.of("1080p"), BEST, SEPARATOR));
    }

    @Test
    void buildsBoundedYtDlpSelector() {
        assertEquals(
                "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b",
                VideoQualityUtils.formatSelectorForHeight(720)
        );
    }
}
