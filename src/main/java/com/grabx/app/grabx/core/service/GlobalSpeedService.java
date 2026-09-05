package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Aggregates the current speed of all active downloads for the footer. */
public final class GlobalSpeedService {
    private static final Pattern SPEED = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(KB|MB|GB|TB)/S",
            Pattern.CASE_INSENSITIVE
    );

    private final ObservableList<DownloadRow> rows;
    private final Consumer<String> speedUpdater;
    private final Consumer<String> summaryUpdater;
    private final Map<DownloadRow, ChangeListener<Object>> listeners = new IdentityHashMap<>();
    private boolean started;

    public GlobalSpeedService(ObservableList<DownloadRow> rows, Consumer<String> speedUpdater) {
        this(rows, speedUpdater, null);
    }

    public GlobalSpeedService(
            ObservableList<DownloadRow> rows,
            Consumer<String> speedUpdater,
            Consumer<String> summaryUpdater
    ) {
        this.rows = rows;
        this.speedUpdater = speedUpdater;
        this.summaryUpdater = summaryUpdater;
    }

    public void start() {
        if (started || rows == null) return;
        started = true;
        for (DownloadRow row : rows) observe(row);
        rows.addListener((ListChangeListener<DownloadRow>) change -> {
            while (change.next()) {
                if (change.wasRemoved()) {
                    for (DownloadRow row : change.getRemoved()) unobserve(row);
                }
                if (change.wasAdded()) {
                    for (DownloadRow row : change.getAddedSubList()) observe(row);
                }
            }
            refresh();
        });
        refresh();
    }

    void refresh() {
        double bytesPerSecond = 0;
        if (rows != null) {
            for (DownloadRow row : rows) {
                if (row == null || row.getState() != DownloadRow.State.DOWNLOADING) continue;
                bytesPerSecond += parseBytesPerSecond(row.speed == null ? null : row.speed.get());
            }
        }
        if (speedUpdater != null) speedUpdater.accept("↓  " + formatSpeed(bytesPerSecond));
        if (summaryUpdater != null) summaryUpdater.accept(formatSummary(rows));
    }

    static String formatSummary(Iterable<DownloadRow> rows) {
        int active = 0;
        int pending = 0;
        int queued = 0;
        int paused = 0;
        int completed = 0;
        if (rows != null) {
            for (DownloadRow row : rows) {
                if (row == null) continue;
                DownloadRow.State state = row.getState();
                if (state == DownloadRow.State.DOWNLOADING) active++;
                else if (state == DownloadRow.State.PENDING) pending++;
                else if (state == DownloadRow.State.QUEUED) queued++;
                else if (state == DownloadRow.State.PAUSED) paused++;
                else if (state == DownloadRow.State.COMPLETED) completed++;
            }
        }
        return active + " downloading  ·  " + pending + " preparing  ·  "
                + queued + " queued  ·  " + paused + " paused  ·  " + completed + " completed";
    }

    static double parseBytesPerSecond(String value) {
        if (value == null) return 0;
        Matcher matcher = SPEED.matcher(value.trim());
        if (!matcher.find()) return 0;
        try {
            double amount = Double.parseDouble(matcher.group(1));
            return switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
                case "TB" -> amount * 1_000_000_000_000d;
                case "GB" -> amount * 1_000_000_000d;
                case "MB" -> amount * 1_000_000d;
                default -> amount * 1_000d;
            };
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1_000_000_000d) {
            return compact(bytesPerSecond / 1_000_000_000d, "GB/s");
        }
        if (bytesPerSecond >= 1_000_000d) {
            return compact(bytesPerSecond / 1_000_000d, "MB/s");
        }
        return compact(Math.max(0, bytesPerSecond) / 1_000d, "KB/s");
    }

    private static String compact(double value, String unit) {
        String number = String.format(Locale.US, "%.1f", value);
        if (number.endsWith(".0")) number = number.substring(0, number.length() - 2);
        return number + " " + unit;
    }

    @SuppressWarnings("unchecked")
    private void observe(DownloadRow row) {
        if (row == null || listeners.containsKey(row)) return;
        ChangeListener<Object> listener = (observable, oldValue, newValue) -> refresh();
        listeners.put(row, listener);
        row.speed.addListener(listener);
        row.state.addListener(listener);
    }

    private void unobserve(DownloadRow row) {
        ChangeListener<Object> listener = listeners.remove(row);
        if (row == null || listener == null) return;
        row.speed.removeListener(listener);
        row.state.removeListener(listener);
    }
}
