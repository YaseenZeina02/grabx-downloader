package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ClearAllService
 * --------------
 * Clears non-active items from the UI list ONLY (does NOT delete files).
 * Active items are: DOWNLOADING / QUEUED / PENDING.
 */
public final class ClearAllService {

    private final ObservableList<DownloadRow> items;

    public ClearAllService(ObservableList<DownloadRow> items) {
        this.items = Objects.requireNonNull(items, "items");
    }

    /**
     * Remove all rows that are NOT active (DOWNLOADING/QUEUED/PENDING).
     * @return number of removed items
     */
    public int clearNonActive() {
        if (items.isEmpty()) return 0;

        final List<DownloadRow> toRemove = new ArrayList<>();

        for (DownloadRow row : items) {
            if (row == null) continue;

            DownloadRow.State st;
            try { st = row.state.get(); } catch (Exception ignored) { continue; }

            if (st != DownloadRow.State.DOWNLOADING
                    && st != DownloadRow.State.QUEUED
                    && st != DownloadRow.State.PENDING) {
                toRemove.add(row);
            }
        }

        if (toRemove.isEmpty()) return 0;

        items.removeAll(toRemove);
        return toRemove.size();
    }
}
