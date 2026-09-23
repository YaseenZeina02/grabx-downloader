package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaTransferProgressTest {
    private static final String SELECTION = """
            [{"format_id":"401","filesize":97616302,"vcodec":"av01"},
             {"format_id":"251","filesize":3838587,"vcodec":"none"}]
            """;

    @Test void includesAudioBeforeVideoCompletesAndNeverResetsAtTheAudioBoundary() {
        var progress = new MediaTransferProgress();
        progress.select(SELECTION);
        assertTrue(progress.combined());
        var video = progress.update("401", 31_400_000, 97_616_302, false);
        assertEquals(101_454_889, video.total());
        assertEquals("31.4 MB / ≈ 101.5 MB", video.sizeText());
        var completeVideo = progress.update("401", 97_616_302, 97_616_302, true);
        assertTrue(completeVideo.fraction() < 1);
        var audio = progress.update("251", 1024, 3_838_587, false);
        assertTrue(audio.fraction() > completeVideo.fraction());
        assertEquals(97_617_326, audio.downloaded());
        assertEquals("Downloading audio", progress.phase("251"));
        assertEquals("Downloading video", progress.phase("401"));
        assertEquals(1, progress.update("251", 3_838_587, 3_838_587, true).fraction());
    }

    @Test void resumedAndRepeatedFinishedEventsCountEachStreamOnce() {
        var progress = new MediaTransferProgress();
        progress.select(SELECTION);
        assertEquals(31_400_000, progress.update("401", 31_400_000, 97_616_302, false).downloaded());
        progress.update("401", 97_616_302, 97_616_302, true);
        progress.update("401", 97_616_302, 97_616_302, true);
        assertEquals(97_617_302, progress.update("251", 1000, 3_838_587, false).downloaded());
        progress.select(SELECTION); // fresh extraction after a failed cached transfer
        assertEquals(1000, progress.update("401", 1000, 97_616_302, false).downloaded());
    }

    @Test void unknownStreamSizesStayUnknownUntilAvailable() {
        var progress = new MediaTransferProgress();
        progress.select("[{\"format_id\":\"v\",\"filesize_approx\":1000},{\"format_id\":\"a\"}]");
        var video = progress.update("v", 500, 1000, false);
        assertEquals(-1, video.total());
        assertEquals(-1, video.fraction());
        assertFalse(video.sizeText().contains(" / "));
        assertEquals("", video.eta("100"));
        progress.update("v", 1100, 1000, true);
        assertEquals(1200, progress.update("a", 10, 100, false).total());
    }

    @Test void totalEtaIncludesTheRemainingAudioAndMalformedMetadataFallsBack() {
        var snapshot = new MediaTransferProgress.Snapshot(400, 1000);
        assertEquals("00:06", snapshot.eta("100"));
        assertEquals("", snapshot.eta("NA"));
        assertEquals("", snapshot.eta("0"));
        var progress = new MediaTransferProgress();
        for (String value : new String[]{"NA", "null", "{}", "[]", "[{\"format_id\":\"single\"}]"}) {
            progress.select(value);
            assertFalse(progress.combined());
        }
    }

    @Test void fragmentedVideoUsesBitrateEstimateInsteadOfTheFirstTinyFragment() {
        var progress = new MediaTransferProgress();
        progress.select("[{\"format_id\":\"v\",\"tbr\":800},{\"format_id\":\"a\",\"filesize\":1000000}]", 60);
        assertEquals(7_000_000, progress.snapshot().total());
        assertEquals(7_000_000, progress.update("v", 712, -1, 712, false).total());
        assertEquals(6_000_000, progress.update("v", 5_000_000, -1, -1, true).total());
    }
}
