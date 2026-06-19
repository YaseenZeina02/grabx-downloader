package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * DownloadService
 * ---------------
 * هدفه يفصل "منطق الداتا + الفلترة + الترتيب الثابت" عن MainController.
 *
 * ✅ Performance:
 * - قائمة واحدة backing (ObservableList)
 * - FilteredList واحد
 * - SortedList واحد بتComparator ثابت orderIndex (عشان الكروت ما تتحرك لما الحالة تتغير)
 *
 * ✅ API بسيطة:
 * - items(): تضيف/تشيل DownloadRow
 * - view(): تربطها مباشرة بالـ ListView
 * - setCombinedFilter(sidebarKey, searchQuery): نفس منطق MainController الحالي
 */
public final class DownloadService {

    private final ObservableList<DownloadRow> items;

    private final FilteredList<DownloadRow> filtered;
    private final SortedList<DownloadRow> sorted;

    private volatile String sidebarKey = "ALL";
    private volatile String searchQuery = "";

    // ✅ ترتيب ثابت: لا يعتمد على state ولا progress
    private volatile Comparator<DownloadRow> comparator =
            Comparator.comparingLong(r -> r.orderIndex);

    public DownloadService() {
        this(FXCollections.observableArrayList());
    }

    public DownloadService(ObservableList<DownloadRow> backing) {
        this.items = Objects.requireNonNull(backing, "backing list");
        this.filtered = new FilteredList<>(this.items, r -> true);
        this.sorted = new SortedList<>(this.filtered);
        this.sorted.setComparator(this.comparator);
        // predicate initially
        applyCombinedFilters();
    }

    /** Backing list: add/remove rows here */
    public ObservableList<DownloadRow> items() {
        return items;
    }

    /** Bind ListView.setItems(view()) */
    public SortedList<DownloadRow> view() {
        return sorted;
    }

    /** If you still need direct access */
    public FilteredList<DownloadRow> filtered() {
        return filtered;
    }

    /** ثابت حسب orderIndex فقط (لتثبيت مكان الكروت) */
    public void setStableOrderIndexSort() {
        setComparator(Comparator.comparingLong(r -> r.orderIndex));
    }

    public void setComparator(Comparator<DownloadRow> cmp) {
        this.comparator = (cmp == null)
                ? Comparator.comparingLong(r -> r.orderIndex)
                : cmp;
        this.sorted.setComparator(this.comparator);
    }

    /** Update sidebar key (ALL / DOWNLOADING / PAUSED / COMPLETED / CANCELLED / MISSING) */
    public void setSidebarKey(String key) {
        this.sidebarKey = normalizeKey(key);
        applyCombinedFilters();
    }

    /** Update search query (from searchField) */
    public void setSearchQuery(String query) {
        this.searchQuery = (query == null) ? "" : query.trim();
        applyCombinedFilters();
    }

    /** Convenience: update both in one call */
    public void setCombinedFilter(String key, String query) {
        this.sidebarKey = normalizeKey(key);
        this.searchQuery = (query == null) ? "" : query.trim();
        applyCombinedFilters();
    }

    /**
     * Optional: set a custom predicate that will be AND'ed with built-in predicate.
     * Useful لو بدك تضيف فلترة إضافية لاحقاً (مثلاً by host/domain).
     */
    public void setExtraPredicate(Predicate<DownloadRow> extra) {
        Predicate<DownloadRow> base = buildPredicate(sidebarKey, searchQuery);
        filtered.setPredicate(extra == null ? base : base.and(extra));
    }

    /** Force re-apply current predicate (sometimes useful after bulk edits). */
    public void refilter() {
        Predicate<? super DownloadRow> p = filtered.getPredicate();
        filtered.setPredicate(p);
    }

    // ===================== Internals =====================

    private void applyCombinedFilters() {
        filtered.setPredicate(buildPredicate(sidebarKey, searchQuery));
    }

    private static String normalizeKey(String key) {
        String k = (key == null) ? "ALL" : key.trim().toUpperCase(Locale.ROOT);
        return k.isEmpty() ? "ALL" : k;
    }

    private static Predicate<DownloadRow> buildPredicate(String sidebarKeyRaw, String queryRaw) {
        final String sidebarKey = normalizeKey(sidebarKeyRaw);

        final String q = (queryRaw == null) ? "" : queryRaw.trim().toLowerCase(Locale.ROOT);

        return row -> {
            if (row == null) return false;

            // ===== 1) Sidebar filter by state =====
            DownloadRow.State st = null;
            try { st = row.state.get(); } catch (Exception ignored) {}

            boolean passSidebar;
            if ("ALL".equals(sidebarKey)) {
                // (حسب منطقك الحالي) ALL لا يظهر Missing
                passSidebar = st != DownloadRow.State.MISSING;
            } else if ("DOWNLOADING".equals(sidebarKey)) {
                passSidebar = st == DownloadRow.State.DOWNLOADING
                        || st == DownloadRow.State.QUEUED
                        || st == DownloadRow.State.PENDING;
            } else if ("PAUSED".equals(sidebarKey)) {
                passSidebar = st == DownloadRow.State.PAUSED;
            } else if ("COMPLETED".equals(sidebarKey)) {
                passSidebar = st == DownloadRow.State.COMPLETED;
            } else if ("CANCELLED".equals(sidebarKey)) {
                // Cancelled + Failed مع بعض
                passSidebar = st == DownloadRow.State.CANCELLED
                        || st == DownloadRow.State.FAILED;
            } else if ("MISSING".equals(sidebarKey)) {
                passSidebar = st == DownloadRow.State.MISSING;
            } else {
                passSidebar = true;
            }

            if (!passSidebar) return false;

            // ===== 2) Search filter =====
            if (q.isEmpty()) return true;

            String title = safeLower(safeGet(row.title));
            String quality = safeLower(row.quality);
            String url = safeLower(row.url);
            String mode = safeLower(row.mode);
            String stateTxt = (st == null) ? "" : st.name().toLowerCase(Locale.ROOT);

            return contains(title, q)
                    || contains(quality, q)
                    || contains(url, q)
                    || contains(mode, q)
                    || contains(stateTxt, q);
        };
    }

    private static boolean contains(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty()) return false;
        return haystack.contains(needle);
    }

    private static String safeLower(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeGet(javafx.beans.property.StringProperty p) {
        try {
            return (p == null) ? "" : p.get();
        } catch (Exception ignored) {
            return "";
        }
    }
}
