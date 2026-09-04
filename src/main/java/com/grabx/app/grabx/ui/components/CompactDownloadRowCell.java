package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Lightweight active-download cell used only by Compact View. */
public final class CompactDownloadRowCell extends ListCell<DownloadRow> {
    private final Consumer<DownloadRow> pause;
    private final Consumer<DownloadRow> resume;
    private final Consumer<DownloadRow> cancel;
    private final Label title = new Label();
    private final Label status = new Label();
    private final Label metrics = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final Button pauseButton = new Button();
    private final Button resumeButton = new Button();
    private final Button cancelButton = new Button();
    private final VBox card = new VBox(7);
    private DownloadRow boundRow;
    private javafx.beans.value.ChangeListener<DownloadRow.State> stateListener;

    public CompactDownloadRowCell(
            Consumer<DownloadRow> pause,
            Consumer<DownloadRow> resume,
            Consumer<DownloadRow> cancel,
            BiConsumer<Button, String> installTooltip,
            BiConsumer<Node, ObservableValue<String>> installDynamicTooltip
    ) {
        this.pause = pause;
        this.resume = resume;
        this.cancel = cancel;

        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setStyle("-fx-background-color: transparent;");
        setPadding(new Insets(5, 0, 5, 0));
        title.getStyleClass().add("gx-compact-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        if (installDynamicTooltip != null) {
            installDynamicTooltip.accept(title, title.textProperty());
        }
        status.getStyleClass().add("gx-compact-status");
        metrics.getStyleClass().add("gx-compact-metrics");
        progress.getStyleClass().add("gx-compact-progress");
        progress.setMaxWidth(Double.MAX_VALUE);

        IconButtonService.setupSvgButton(pauseButton, IconButtonService.PAUSE);
        IconButtonService.setupSvgButton(resumeButton, IconButtonService.PLAY);
        IconButtonService.setupSvgButton(cancelButton, IconButtonService.CANCEL);
        pauseButton.getStyleClass().addAll("gx-compact-action", "gx-compact-pause");
        resumeButton.getStyleClass().addAll("gx-compact-action", "gx-compact-resume");
        cancelButton.getStyleClass().addAll("gx-compact-action", "gx-compact-cancel");
        if (installTooltip != null) {
            installTooltip.accept(pauseButton, "Pause");
            installTooltip.accept(resumeButton, "Resume");
            installTooltip.accept(cancelButton, "Cancel");
        }

        HBox actions = new HBox(5, pauseButton, resumeButton, cancelButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(8, title, actions);
        header.setAlignment(Pos.CENTER_LEFT);
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(8, status, footerSpacer, metrics);
        footer.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("gx-compact-card");
        card.getChildren().addAll(header, progress, footer);

        pauseButton.setOnAction(event -> fire(pause));
        resumeButton.setOnAction(event -> fire(resume));
        cancelButton.setOnAction(event -> fire(cancel));
    }

    @Override
    protected void updateItem(DownloadRow row, boolean empty) {
        super.updateItem(row, empty);
        unbindRow();
        if (empty || row == null) {
            setGraphic(null);
            return;
        }
        boundRow = row;
        title.textProperty().bind(row.title);
        status.textProperty().bind(row.status);
        metrics.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> compactMetrics(row.speed.get(), row.eta.get()),
                row.speed, row.eta
        ));
        progress.progressProperty().bind(row.progress);
        stateListener = (observable, oldState, newState) -> updateActions(newState);
        row.stateProperty().addListener(stateListener);
        updateActions(row.getState());
        setGraphic(card);
    }

    private void updateActions(DownloadRow.State state) {
        boolean paused = state == DownloadRow.State.PAUSED;
        show(pauseButton, state == DownloadRow.State.DOWNLOADING
                || state == DownloadRow.State.PENDING || state == DownloadRow.State.QUEUED);
        show(resumeButton, paused);
        show(cancelButton, true);
    }

    private static void show(Button button, boolean visible) {
        button.setVisible(visible);
        button.setManaged(visible);
    }

    private void fire(Consumer<DownloadRow> action) {
        DownloadRow row = getItem();
        if (action != null && row != null) action.accept(row);
    }

    private void unbindRow() {
        title.textProperty().unbind();
        status.textProperty().unbind();
        metrics.textProperty().unbind();
        progress.progressProperty().unbind();
        if (boundRow != null && stateListener != null) {
            boundRow.stateProperty().removeListener(stateListener);
        }
        boundRow = null;
        stateListener = null;
    }

    private static String compactMetrics(String speed, String eta) {
        String safeSpeed = speed == null ? "" : speed.trim();
        String safeEta = eta == null ? "" : eta.trim();
        if (safeSpeed.isEmpty()) return safeEta;
        if (safeEta.isEmpty()) return safeSpeed;
        return safeSpeed + "  ·  " + safeEta;
    }
}
