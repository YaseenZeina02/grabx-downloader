package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.core.service.DownloadStateCoordinator;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import java.util.function.BiConsumer;

/** Configures and refreshes the main downloads list view. */
public final class DownloadListViewService {
    private final ListView<DownloadRow> downloadsList;
    private final ObservableList<DownloadRow> downloadsView;
    private final BorderPane root;
    private final DownloadStateCoordinator stateCoordinator;
    private final DownloadRowActions rowActions;
    private final BiConsumer<Button, String> iconButtonSetup;
    private final BiConsumer<Button, String> tooltipInstaller;
    private final BiConsumer<Node, ObservableValue<String>> hoverTextInstaller;
    private final Icons icons;

    public DownloadListViewService(
            ListView<DownloadRow> downloadsList,
            ObservableList<DownloadRow> downloadsView,
            BorderPane root,
            DownloadStateCoordinator stateCoordinator,
            DownloadRowActions rowActions,
            BiConsumer<Button, String> iconButtonSetup,
            BiConsumer<Button, String> tooltipInstaller,
            BiConsumer<Node, ObservableValue<String>> hoverTextInstaller,
            Icons icons
    ) {
        this.downloadsList = downloadsList;
        this.downloadsView = downloadsView;
        this.root = root;
        this.stateCoordinator = stateCoordinator;
        this.rowActions = rowActions;
        this.iconButtonSetup = iconButtonSetup;
        this.tooltipInstaller = tooltipInstaller;
        this.hoverTextInstaller = hoverTextInstaller;
        this.icons = icons;
    }

    public void initialize() {
        if (downloadsList == null || stateCoordinator == null || rowActions == null || icons == null) return;

        downloadsList.setItems(downloadsView);
        downloadsList.setCellFactory(list -> new DownloadRowCell(
                stateCoordinator::pause,
                stateCoordinator::resume,
                stateCoordinator::cancel,
                rowActions::openDownloadLink,
                rowActions::openFolderForDownloadRow,
                rowActions::retryDownloadRow,
                rowActions::clearDownloadRow,
                iconButtonSetup,
                tooltipInstaller,
                hoverTextInstaller,
                icons.pause(),
                icons.resume(),
                icons.cancel(),
                icons.openLink(),
                icons.folder(),
                icons.retry(),
                icons.clear()
        ));
        downloadsList.setSelectionModel(new NoSelectionModel<>());
        installWindowActivationRefresh();
    }

    private void installWindowActivationRefresh() {
        Platform.runLater(() -> {
            try {
                if (root == null || root.getScene() == null || root.getScene().getWindow() == null) return;
                root.getScene().getWindow().focusedProperty().addListener((observable, wasFocused, isFocused) -> {
                    if (!isFocused) return;
                    Platform.runLater(() -> {
                        try {
                            downloadsList.refresh();
                            root.requestLayout();
                        } catch (Exception ignored) {
                        }
                    });
                });
            } catch (Exception ignored) {
            }
        });
    }

    public record Icons(
            String pause,
            String resume,
            String cancel,
            String openLink,
            String folder,
            String retry,
            String clear
    ) {
    }
}
