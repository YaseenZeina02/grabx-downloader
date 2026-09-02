package com.grabx.app.grabx.core.service;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Coordinates toolbar actions that operate on all downloads. */
public final class BulkDownloadActionsService {
    private final IntSupplier cancelAll;
    private final IntSupplier pauseAll;
    private final IntSupplier resumeAll;
    private final IntSupplier clearAll;
    private final BooleanSupplier itemsEmpty;
    private final Runnable refreshMissing;
    private final Runnable refilter;
    private final Runnable clearHistory;
    private final Runnable saveHistory;
    private final Consumer<String> statusUpdater;

    public BulkDownloadActionsService(
            IntSupplier cancelAll,
            IntSupplier pauseAll,
            IntSupplier resumeAll,
            IntSupplier clearAll,
            BooleanSupplier itemsEmpty,
            Runnable refreshMissing,
            Runnable refilter,
            Runnable clearHistory,
            Runnable saveHistory,
            Consumer<String> statusUpdater
    ) {
        this.cancelAll = cancelAll;
        this.pauseAll = pauseAll;
        this.resumeAll = resumeAll;
        this.clearAll = clearAll;
        this.itemsEmpty = itemsEmpty;
        this.refreshMissing = refreshMissing;
        this.refilter = refilter;
        this.clearHistory = clearHistory;
        this.saveHistory = saveHistory;
        this.statusUpdater = statusUpdater;
    }

    public int cancelAll() {
        return runStateAction(cancelAll, "Cancelled", "Nothing to cancel");
    }

    public int pauseAll() {
        return runStateAction(pauseAll, "Paused", "Nothing to pause");
    }

    public int resumeAll() {
        return runStateAction(resumeAll, "Resumed", "Nothing to resume");
    }

    public int clearAll() {
        int removed = safeCount(clearAll);
        if (removed > 0) {
            safeRun(refreshMissing);
            safeRun(refilter);
            if (isEmpty()) safeRun(clearHistory); else safeRun(saveHistory);
        }
        updateStatus(removed == 0 ? "Nothing to clear" : "Cleared " + removed + " item(s)");
        return removed;
    }

    private int runStateAction(IntSupplier action, String verb, String emptyMessage) {
        int affected = safeCount(action);
        updateStatus(affected > 0 ? verb + " " + affected + " item(s)" : emptyMessage);
        return affected;
    }

    private boolean isEmpty() {
        try {
            return itemsEmpty != null && itemsEmpty.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private int safeCount(IntSupplier action) {
        try {
            return action == null ? 0 : Math.max(0, action.getAsInt());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void updateStatus(String text) {
        try {
            if (statusUpdater != null) statusUpdater.accept(text);
        } catch (Exception ignored) {
        }
    }

    private static void safeRun(Runnable action) {
        try {
            if (action != null) action.run();
        } catch (Exception ignored) {
        }
    }
}
