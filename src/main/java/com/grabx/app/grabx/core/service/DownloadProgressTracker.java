package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DownloadProgressTracker {
    private static final double ROUNDING_TOLERANCE = 0.003;

    private final ConcurrentHashMap<DownloadRow, Double> progressByRow = new ConcurrentHashMap<>();

    public Map<DownloadRow, Double> progressByRow() {
        return progressByRow;
    }

    public void applyMonotonic(DownloadRow row, double progress) {
        if (row == null || progress < 0 || Double.isNaN(progress)) return;

        double boundedProgress = Math.min(progress, 1.0);
        Double previousValue = progressByRow.get(row);
        if (previousValue == null) {
            progressByRow.put(row, boundedProgress);
            row.progress.set(boundedProgress);
            return;
        }

        double previous = previousValue;
        if (boundedProgress + ROUNDING_TOLERANCE < previous) return;
        if (boundedProgress > previous) progressByRow.put(row, boundedProgress);
        row.progress.set(Math.max(previous, boundedProgress));
    }
}
