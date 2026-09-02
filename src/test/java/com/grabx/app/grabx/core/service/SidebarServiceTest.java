package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.ui.sidebar.SidebarItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarServiceTest {
    @Test
    void normalizesFilterKeys() {
        assertEquals("ALL", SidebarService.normalizeKey(null));
        assertEquals("ALL", SidebarService.normalizeKey("  "));
        assertEquals("MISSING", SidebarService.normalizeKey(" missing "));
    }

    @Test
    void detectsMissingDownloads() {
        DownloadRow completed = row(DownloadRow.State.COMPLETED);
        DownloadRow missing = row(DownloadRow.State.MISSING);

        assertFalse(SidebarService.hasMissing(List.of(completed)));
        assertTrue(SidebarService.hasMissing(List.of(completed, missing)));
    }

    @Test
    void findsSidebarItemCaseInsensitively() {
        List<SidebarItem> items = List.of(
                new SidebarItem("ALL", "All"),
                new SidebarItem("MISSING", "Missing")
        );

        assertEquals(1, SidebarService.findItem(items, "missing"));
        assertEquals(-1, SidebarService.findItem(items, "paused"));
    }

    private static DownloadRow row(DownloadRow.State state) {
        DownloadRow row = new DownloadRow("url", "title", 1, "/tmp", "Video", "720p");
        row.setState(state);
        return row;
    }
}
