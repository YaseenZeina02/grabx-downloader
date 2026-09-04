package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Parent;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Starts and owns history, missing-file, and clipboard monitoring. */
public final class DownloadMonitoringService {
    private final ObservableList<DownloadRow> items;
    private final ScheduledExecutorService delayExecutor;
    private final Parent root;
    private final Consumer<DownloadRow> attachHistory;
    private final Runnable saveHistory;
    private final Runnable refreshMissingSidebar;
    private final Runnable refilter;
    private final Consumer<String> openAddLink;
    private final Predicate<String> isHttpUrl;

    private MissingWatcherService missingWatcher;
    private ClipboardService clipboardWatcher;
    private boolean historyObserved;

    public DownloadMonitoringService(
            ObservableList<DownloadRow> items,
            ScheduledExecutorService delayExecutor,
            Parent root,
            Consumer<DownloadRow> attachHistory,
            Runnable saveHistory,
            Runnable refreshMissingSidebar,
            Runnable refilter,
            Consumer<String> openAddLink,
            Predicate<String> isHttpUrl
    ) {
        this.items = items;
        this.delayExecutor = delayExecutor;
        this.root = root;
        this.attachHistory = attachHistory;
        this.saveHistory = saveHistory;
        this.refreshMissingSidebar = refreshMissingSidebar;
        this.refilter = refilter;
        this.openAddLink = openAddLink;
        this.isHttpUrl = isHttpUrl;
    }

    public void start() {
        observeDownloads();
        safeRun(refreshMissingSidebar);

        if (items != null && delayExecutor != null && missingWatcher == null) {
            missingWatcher = new MissingWatcherService(items, delayExecutor, () -> {
                safeRun(refilter);
                Platform.runLater(() -> safeRun(refreshMissingSidebar));
            });
            safeRun(missingWatcher::start);
        }

        if (root != null && clipboardWatcher == null) {
            clipboardWatcher = new ClipboardService(root, openAddLink, isHttpUrl);
            safeRun(clipboardWatcher::start);
        }
    }

    public void stop() {
        safeRun(missingWatcher == null ? null : missingWatcher::stop);
        safeRun(clipboardWatcher == null ? null : clipboardWatcher::stop);
        missingWatcher = null;
        clipboardWatcher = null;
    }

    void observeDownloads() {
        if (items == null || historyObserved) return;
        historyObserved = true;
        items.addListener((ListChangeListener<DownloadRow>) change -> {
            boolean addedAny = false;
            while (change.next()) {
                if (!change.wasAdded()) continue;
                addedAny = true;
                for (DownloadRow row : change.getAddedSubList()) {
                    if (row != null) safeAccept(attachHistory, row);
                }
            }
            if (addedAny) safeRun(saveHistory);
        });
    }

    private static void safeRun(Runnable action) {
        try {
            if (action != null) action.run();
        } catch (Exception ignored) {
        }
    }

    private static <T> void safeAccept(Consumer<T> action, T value) {
        try {
            if (action != null) action.accept(value);
        } catch (Exception ignored) {
        }
    }
}
