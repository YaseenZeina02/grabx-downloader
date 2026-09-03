package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DownloadTitleServiceTest {
    @Test
    void parsesEscapedAndUnicodeJsonTitles() {
        assertEquals("A \"quoted\" عنوان", DownloadTitleService.parseTitle(
                "{\"title\":\"A \\\"quoted\\\" \\u0639\\u0646\\u0648\\u0627\\u0646\"}"
        ));
        assertNull(DownloadTitleService.parseTitle("{}"));
        assertNull(DownloadTitleService.parseTitle("not-json"));
    }

    @Test
    void addsSuffixForMatchingDownloadTarget() {
        List<DownloadRow> rows = new ArrayList<>();
        DownloadRow first = row("Song", "/downloads", "Video", "720p");
        DownloadRow second = row("Song (1)", "/downloads", "Video", "720p");
        DownloadRow current = row("Preparing", "/downloads", "Video", "720p");
        rows.addAll(List.of(first, second, current));
        DownloadTitleService service = service(rows, null);

        assertEquals("Song (2)", service.uniqueTitle("Song", current));
    }

    @Test
    void doesNotAddSuffixForDifferentTarget() {
        List<DownloadRow> rows = new ArrayList<>();
        DownloadRow existing = row("Song", "/other", "Video", "720p");
        DownloadRow current = row("Preparing", "/downloads", "Video", "720p");
        rows.addAll(List.of(existing, current));

        assertEquals("Song", service(rows, null).uniqueTitle("Song", current));
    }

    @Test
    void appliesFetchedTitleAndUpdatesStatus() {
        List<DownloadRow> rows = new ArrayList<>();
        DownloadRow current = row("Preparing", "/downloads", "Video", "720p");
        rows.add(current);
        AtomicReference<String> status = new AtomicReference<>();
        DownloadTitleService service = service(rows, status);

        service.applyResolvedTitle(current, "https://youtu.be/id", "  Video title  ");

        assertEquals("Video title", current.title.get());
        assertEquals("Queued: Video title", status.get());
    }

    @Test
    void fallsBackToShortenedUrl() {
        DownloadRow current = row("Preparing", "/downloads", "Video", "720p");
        DownloadTitleService service = service(List.of(current), null);
        String url = "https://example.com/a/very/long/path/that/needs/to/be/shortened";

        service.applyResolvedTitle(current, url, null);

        assertEquals(DownloadTitleService.shorten(url), current.title.get());
    }

    private static DownloadTitleService service(
            Iterable<DownloadRow> rows, AtomicReference<String> status
    ) {
        return new DownloadTitleService(
                rows,
                Runnable::run,
                status == null ? null : status::set,
                url -> null
        );
    }

    private static DownloadRow row(String title, String folder, String mode, String quality) {
        return new DownloadRow("https://youtu.be/id", title, 1, folder, mode, quality);
    }
}
