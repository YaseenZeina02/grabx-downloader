package com.grabx.app.grabx;

import com.grabx.app.grabx.ui.sidebar.SidebarItem;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

public class MainController {

    @FXML private Label statusText;
    @FXML private BorderPane root;

    @FXML
    private Button addLinkButton;

    // ✅ هذا هو الـID اللي نربطه بعنوان Downloads (ديناميكي)
    @FXML private Label contentTitle;

    @FXML private ListView<SidebarItem> sidebarList;

    @FXML
    public void onAddLink(ActionEvent event) {
        if (statusText != null) statusText.setText("Add Link clicked");
    }

    @FXML
    public void onSettings(ActionEvent event) {
        if (statusText != null) statusText.setText("Settings clicked");
    }

    @FXML
    public void onMiniMode(ActionEvent event) {
        if (statusText != null) statusText.setText("Mini Mode clicked");
    }

    @FXML
    public void initialize() {
        javafx.application.Platform.runLater(() -> root.requestFocus());

//        root.requestFocus();

        sidebarList.getItems().setAll(
                new SidebarItem("ALL", "All"),
                new SidebarItem("DOWNLOADING", "Downloading"),
                new SidebarItem("PAUSED", "Paused"),
                new SidebarItem("COMPLETED", "Completed"),
                new SidebarItem("CANCELLED", "Cancelled")
        );

        // ✅ Keep consistent row height, but allow the ListView to grow with the sidebar
        sidebarList.setFixedCellSize(44);
        sidebarList.setPrefHeight(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
        sidebarList.setMaxHeight(Double.MAX_VALUE);

        // عرض الاسم داخل الخلايا
        sidebarList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SidebarItem item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getTitle());
            }
        });

        sidebarList.getSelectionModel().selectFirst();

        SidebarItem first = sidebarList.getSelectionModel().getSelectedItem();
        if (contentTitle != null && first != null) contentTitle.setText(first.getTitle());

        sidebarList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;

            if (contentTitle != null) contentTitle.setText(newV.getTitle());
            if (statusText != null) statusText.setText("Filter: " + newV.getTitle());

            // TODO لاحقاً: applyFilter(newV.getKey());

        });

    }
    public void onFilterAll() { /* TODO */ }
    public void onFilterDownloading() { /* TODO */ }
    public void onFilterPaused() { /* TODO */ }
    public void onFilterCompleted() { /* TODO */ }
    public void onFilterCancelled() { /* TODO */ }

}
