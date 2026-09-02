package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.ui.sidebar.SidebarItem;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.Locale;

public final class SidebarService {
    private static final String ALL = "ALL";
    private static final String MISSING = "MISSING";
    private static final List<SidebarItem> DEFAULT_ITEMS = List.of(
            new SidebarItem(ALL, "All"),
            new SidebarItem("DOWNLOADING", "Downloading"),
            new SidebarItem("PAUSED", "Paused"),
            new SidebarItem("COMPLETED", "Completed"),
            new SidebarItem("CANCELLED", "Cancelled")
    );

    private final ListView<SidebarItem> sidebar;
    private final Label contentTitle;
    private final Label statusText;
    private final TextField searchField;
    private final ObservableList<DownloadRow> downloadItems;
    private final DownloadService downloadService;
    private final SidebarItem missingItem = new SidebarItem(MISSING, "Missing");

    private volatile String currentKey = ALL;

    public SidebarService(
            ListView<SidebarItem> sidebar,
            Label contentTitle,
            Label statusText,
            TextField searchField,
            ObservableList<DownloadRow> downloadItems,
            DownloadService downloadService
    ) {
        this.sidebar = sidebar;
        this.contentTitle = contentTitle;
        this.statusText = statusText;
        this.searchField = searchField;
        this.downloadItems = downloadItems;
        this.downloadService = downloadService;
    }

    public void initialize() {
        if (sidebar == null || downloadService == null) return;

        sidebar.getItems().setAll(DEFAULT_ITEMS);
        sidebar.setFixedCellSize(44);
        sidebar.setPrefHeight(Region.USE_COMPUTED_SIZE);
        sidebar.setMaxHeight(Double.MAX_VALUE);
        sidebar.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SidebarItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTitle());
            }
        });

        sidebar.getSelectionModel().selectFirst();
        updateSelection(sidebar.getSelectionModel().getSelectedItem(), false);
        sidebar.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldItem, newItem) -> updateSelection(newItem, true)
        );

        if (searchField != null) {
            searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
            searchField.setOnKeyPressed(event -> {
                if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                    searchField.clear();
                    event.consume();
                }
            });
        }
        applyFilter();
    }

    public void refreshMissingItem() {
        Platform.runLater(() -> {
            if (sidebar == null) return;
            boolean hasMissing = hasMissing(downloadItems);
            int missingIndex = findItem(sidebar.getItems(), MISSING);

            if (hasMissing && missingIndex < 0) {
                sidebar.getItems().add(missingItem);
            } else if (!hasMissing && missingIndex >= 0) {
                SidebarItem selected = sidebar.getSelectionModel().getSelectedItem();
                boolean missingSelected = selected != null && MISSING.equalsIgnoreCase(selected.getKey());
                sidebar.getItems().remove(missingIndex);
                if (missingSelected) sidebar.getSelectionModel().selectFirst();
            }
        });
    }

    public void refilter() {
        applyFilter();
    }

    public String currentKey() {
        return currentKey;
    }

    static String normalizeKey(String key) {
        if (key == null || key.isBlank()) return ALL;
        return key.trim().toUpperCase(Locale.ROOT);
    }

    static boolean hasMissing(Iterable<DownloadRow> rows) {
        if (rows == null) return false;
        for (DownloadRow row : rows) {
            if (row != null && row.getState() == DownloadRow.State.MISSING) return true;
        }
        return false;
    }

    static int findItem(List<SidebarItem> items, String key) {
        if (items == null) return -1;
        for (int index = 0; index < items.size(); index++) {
            SidebarItem item = items.get(index);
            if (item != null && normalizeKey(key).equals(normalizeKey(item.getKey()))) return index;
        }
        return -1;
    }

    private void updateSelection(SidebarItem item, boolean showStatus) {
        if (item == null) return;
        currentKey = normalizeKey(item.getKey());
        if (contentTitle != null) contentTitle.setText(item.getTitle());
        if (showStatus && statusText != null) statusText.setText("Filter: " + item.getTitle());
        applyFilter();
    }

    private void applyFilter() {
        if (downloadService == null) return;
        downloadService.setCombinedFilter(
                currentKey,
                searchField == null ? "" : searchField.getText()
        );
    }
}
