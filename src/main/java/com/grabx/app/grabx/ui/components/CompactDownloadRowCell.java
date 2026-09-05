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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Lightweight active-download cell used only by Compact View. */
public final class CompactDownloadRowCell extends ListCell<DownloadRow> {
    private final Consumer<DownloadRow> pause;
    private final Consumer<DownloadRow> resume;
    private final Consumer<DownloadRow> cancel;
    private final Label title = new Label();
    private final javafx.scene.image.ImageView thumbnail = new javafx.scene.image.ImageView();
    private final StackPane titleViewport = new StackPane(title);
    private final BorderPane mediaHeader = new BorderPane();
    private final Label status = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final Button pauseButton = new Button();
    private final Button resumeButton = new Button();
    private final Button cancelButton = new Button();
    private final VBox card = new VBox(6);
    private DownloadRow boundRow;
    private javafx.beans.value.ChangeListener<DownloadRow.State> stateListener;
    private javafx.beans.value.ChangeListener<String> titleListener;
    private javafx.beans.value.ChangeListener<String> thumbnailListener;
    private javafx.animation.Timeline titleMarquee;
    private boolean titleRtl;

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
        title.setTextOverrun(OverrunStyle.CLIP);
        title.setWrapText(false);
        title.setMinWidth(Region.USE_PREF_SIZE);
        title.setMaxWidth(Region.USE_PREF_SIZE);
        titleViewport.setMinWidth(0);
        titleViewport.setMaxWidth(Double.MAX_VALUE);
        titleViewport.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.shape.Rectangle titleClip = new javafx.scene.shape.Rectangle();
        titleClip.widthProperty().bind(titleViewport.widthProperty());
        titleClip.heightProperty().bind(titleViewport.heightProperty());
        titleViewport.setClip(titleClip);
        titleViewport.widthProperty().addListener((observable, oldWidth, newWidth) -> {
            if (boundRow != null && newWidth.doubleValue() > 0) scheduleTitleMarquee();
        });
        thumbnail.setFitWidth(46);
        thumbnail.setFitHeight(46);
        thumbnail.setPreserveRatio(false);
        javafx.scene.shape.Rectangle thumbnailClip = new javafx.scene.shape.Rectangle(46, 46);
        thumbnailClip.setArcWidth(13);
        thumbnailClip.setArcHeight(13);
        thumbnail.setClip(thumbnailClip);
        thumbnail.getStyleClass().add("gx-compact-item-thumb");
        mediaHeader.setCenter(titleViewport);
        mediaHeader.setLeft(thumbnail);
        BorderPane.setMargin(thumbnail, new Insets(0, 9, 0, 0));
        mediaHeader.getStyleClass().add("gx-compact-media-header");
        if (installDynamicTooltip != null) {
            installDynamicTooltip.accept(title, title.textProperty());
        }
        status.getStyleClass().add("gx-compact-status");
        status.setMaxWidth(Double.MAX_VALUE);
        status.setAlignment(Pos.CENTER);
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
        Region controlSpacer = new Region();
        HBox.setHgrow(controlSpacer, Priority.ALWAYS);
        HBox controls = new HBox(8, controlSpacer, actions);
        controls.setAlignment(Pos.CENTER_RIGHT);
        card.getStyleClass().add("gx-compact-card");
        ObservableValue<? extends Number> availableCardWidth =
                javafx.beans.binding.Bindings.max(0, widthProperty().subtract(10));
        card.prefWidthProperty().bind(availableCardWidth);
        card.maxWidthProperty().bind(availableCardWidth);
        card.getChildren().addAll(mediaHeader, controls, progress, status);

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
        titleListener = (observable, oldTitle, newTitle) -> updateMedia(newTitle, row.thumbUrl.get());
        thumbnailListener = (observable, oldUrl, newUrl) -> updateMedia(row.title.get(), newUrl);
        row.title.addListener(titleListener);
        row.thumbUrl.addListener(thumbnailListener);
        updateMedia(row.title.get(), row.thumbUrl.get());
        status.textProperty().bind(row.status);
        progress.progressProperty().bind(row.progress);
        stateListener = (observable, oldState, newState) -> updateActions(newState);
        row.stateProperty().addListener(stateListener);
        updateActions(row.getState());
        setGraphic(card);
    }

    private void updateActions(DownloadRow.State state) {
        boolean paused = state == DownloadRow.State.PAUSED;
        // This cell is only used by DownloadService.activeView(). Keep the
        // controls stable during brief state transitions: every active task
        // can be paused, while a paused task gets Resume in the same position.
        show(pauseButton, !paused);
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
        if (titleMarquee != null) titleMarquee.stop();
        titleMarquee = null;
        status.textProperty().unbind();
        progress.progressProperty().unbind();
        if (boundRow != null && titleListener != null) boundRow.title.removeListener(titleListener);
        if (boundRow != null && thumbnailListener != null) boundRow.thumbUrl.removeListener(thumbnailListener);
        if (boundRow != null && stateListener != null) {
            boundRow.stateProperty().removeListener(stateListener);
        }
        boundRow = null;
        stateListener = null;
        titleListener = null;
        thumbnailListener = null;
    }

    private void updateMedia(String value, String thumbnailUrl) {
        String safeTitle = value == null || value.isBlank() ? "Preparing download" : value.trim();
        titleRtl = safeTitle.codePoints().anyMatch(cp -> cp >= 0x0590 && cp <= 0x08FF);
        title.setText(safeTitle);
        title.setTranslateX(0);
        titleViewport.setAlignment(titleRtl ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        boolean hasThumbnail = thumbnailUrl != null && !thumbnailUrl.isBlank();
        thumbnail.setVisible(hasThumbnail);
        thumbnail.setManaged(hasThumbnail);
        thumbnail.setImage(hasThumbnail ? new javafx.scene.image.Image(thumbnailUrl, true) : null);
        Node media = hasThumbnail ? thumbnail : FileTypePreview.create(value,
                boundRow == null ? null : boundRow.mode, true);
        mediaHeader.setLeft(media);
        BorderPane.setMargin(media, new Insets(0, 9, 0, 0));

        if (titleMarquee != null) titleMarquee.stop();
        titleMarquee = null;
        scheduleTitleMarquee();
    }

    private void scheduleTitleMarquee() {
        if (titleMarquee != null) titleMarquee.stop();
        titleMarquee = null;
        javafx.application.Platform.runLater(this::startTitleMarquee);
    }

    private void startTitleMarquee() {
        if (boundRow == null || title.getText() == null) return;
        card.applyCss();
        card.layout();
        title.applyCss();
        double viewportWidth = titleViewport.getWidth();
        double textWidth = Math.max(title.prefWidth(-1), title.getLayoutBounds().getWidth());
        if (viewportWidth <= 0 || textWidth <= viewportWidth) {
            title.setTranslateX(0);
            return;
        }

        double start = titleRtl ? viewportWidth : -textWidth;
        double end = titleRtl ? -textWidth : viewportWidth;
        double seconds = Math.max(7.0, (viewportWidth + textWidth) / 34.0);
        title.setTranslateX(start);
        titleMarquee = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.ZERO,
                        new javafx.animation.KeyValue(title.translateXProperty(), start)),
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(seconds),
                        new javafx.animation.KeyValue(title.translateXProperty(), end,
                                javafx.animation.Interpolator.LINEAR))
        );
        titleMarquee.setCycleCount(javafx.animation.Animation.INDEFINITE);
        titleMarquee.play();
    }

}
