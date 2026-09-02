package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/**
 * Temporary placeholder while extracting the real download row cell from MainController.
 *
 * The full cell implementation currently still lives in MainController.ensureDownloadsListView().
 * We keep this class compiling first, then move the implementation in small safe steps.
 *
 * Extraction plan:
 * 1) Keep MainController working with its current anonymous ListCell.
 * 2) Move only UI construction first.
 * 3) Inject actions/dependencies through callbacks after the UI compiles.
 * 4) Replace MainController cell factory only after DownloadRowCell reaches feature parity.
 */
public class DownloadRowCell extends ListCell<DownloadRow> {
    private final Label title = new Label();
    private final Label meta = new Label();
    private final Label status = new Label();

    private final Label speedDot = new Label("·");
    private final Label speed = new Label();
    private final Label etaDot = new Label("·");
    private final Label eta = new Label();

    private final ProgressBar bar = new ProgressBar(0);

    private double targetProgress = 0.0;
    private double visualProgress = 0.0;
    private DownloadRow progressBoundRow;
    private javafx.beans.value.ChangeListener<Number> progressListener;

    private final AnimationTimer progressSmoother = new AnimationTimer() {
        private long lastNs = 0;

        @Override
        public void handle(long now) {
            if (lastNs == 0) {
                lastNs = now;
                return;
            }

            if (targetProgress < 0) {
                if (bar.getProgress() != ProgressIndicator.INDETERMINATE_PROGRESS) {
                    bar.progressProperty().unbind();
                    bar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                }
                return;
            }

            if (bar.getProgress() < 0) {
                visualProgress = clamp01(targetProgress);
                bar.progressProperty().unbind();
                bar.setProgress(visualProgress);
                return;
            }

            double tp = clamp01(targetProgress);
            double dt = (now - lastNs) / 1_000_000_000.0;
            lastNs = now;

            double k = 12.0;
            double alpha = 1.0 - Math.exp(-k * dt);

            visualProgress = visualProgress + (tp - visualProgress) * alpha;

            if (Math.abs(tp - visualProgress) < 0.0015) {
                visualProgress = tp;
            }

            bar.progressProperty().unbind();
            bar.setProgress(clamp01(visualProgress));
        }

        @Override
        public void stop() {
            super.stop();
            lastNs = 0;
        }
    };

    private final Label sizeLabel = new Label();

    private final Button pauseBtn = new Button();
    private final Button resumeBtn = new Button();
    private final Button cancelBtn = new Button();
    private final Button openLinkBtn = new Button();
    private final Button folderBtn = new Button();
    private final Button retryBtn = new Button();
    private final Button clearBtn = new Button();

    private final StackPane thumbBox = new StackPane();
    private final ImageView thumb = new ImageView();
    private final Label thumbPlaceholder = new Label("•••");
    private final FadeTransition thumbPulse = new FadeTransition(Duration.millis(850), thumbPlaceholder);

    private javafx.beans.value.ChangeListener<String> thumbUrlListener;
    private javafx.beans.value.ChangeListener<Path> outputFileListener;
    private String lastThumbUrl;

    private static final Map<String, Image> THUMB_IMAGE_CACHE = new ConcurrentHashMap<>();

    private final HBox actions = new HBox(8);
    private final VBox textBox = new VBox(6);
    private final HBox headerRow = new HBox(12);
    private final HBox footerRow = new HBox(10);
    private final VBox card = new VBox(10);

    private javafx.beans.value.ChangeListener<DownloadRow.State> stateListener;

    private Consumer<DownloadRow> onPause;
    private Consumer<DownloadRow> onResume;
    private Consumer<DownloadRow> onCancel;
    private Consumer<DownloadRow> onOpenLink;
    private Consumer<DownloadRow> onOpenFolder;
    private Consumer<DownloadRow> onRetry;
    private Consumer<DownloadRow> onClear;

    private BiConsumer<Button, String> iconButtonSetup;
    private BiConsumer<Button, String> tooltipInstaller;
    private BiConsumer<javafx.scene.Node, javafx.beans.value.ObservableValue<String>> hoverTextInstaller;

    public DownloadRowCell() {
        setStyle("-fx-background-color: transparent;");
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        // A ListCell otherwise computes its preferred width from the graphic.
        // With a long title that creates a feedback loop where the cell/card
        // grows wider than the ListView viewport and gets clipped on the right.
        setPrefWidth(0);
        setMaxWidth(Double.MAX_VALUE);

        title.getStyleClass().add("gx-task-title");
        title.setWrapText(false);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setEllipsisString("…");
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);

        meta.setTextOverrun(OverrunStyle.ELLIPSIS);
        meta.setMinWidth(0);
        meta.setMaxWidth(Double.MAX_VALUE);

        meta.getStyleClass().add("gx-task-meta");
        status.getStyleClass().addAll("gx-task-status", "gx-task-metric");
        speed.getStyleClass().addAll("gx-task-status", "gx-task-metric");
        eta.getStyleClass().addAll("gx-task-status", "gx-task-metric");
        sizeLabel.getStyleClass().addAll("gx-task-status", "gx-task-metric");
        speedDot.getStyleClass().addAll("gx-task-status", "gx-task-metric");
        etaDot.getStyleClass().addAll("gx-task-status", "gx-task-metric");

        speedDot.setOpacity(0.6);
        etaDot.setOpacity(0.6);
        speedDot.setMinWidth(10);
        speedDot.setPrefWidth(10);
        etaDot.setMinWidth(10);
        etaDot.setPrefWidth(10);

        bar.getStyleClass().add("gx-task-progress");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(6);
        bar.setMinHeight(6);

        thumb.setFitWidth(108);
        thumb.setFitHeight(66);
        thumb.setPreserveRatio(true);
        thumb.setSmooth(true);
        Rectangle thumbClip = new Rectangle(108, 66);
        thumbClip.setArcWidth(24);
        thumbClip.setArcHeight(24);
        thumb.setClip(thumbClip);

        thumbBox.getStyleClass().add("gx-task-thumb");
        thumbPlaceholder.getStyleClass().add("gx-task-thumb-placeholder");
        thumbBox.getChildren().addAll(thumb, thumbPlaceholder);

        thumbPulse.setFromValue(0.35);
        thumbPulse.setToValue(0.9);
        thumbPulse.setAutoReverse(true);
        thumbPulse.setCycleCount(Animation.INDEFINITE);

        setFallbackButtonText();

        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setFillHeight(true);
        actions.setMinHeight(40);
        // Never let a long title push the action buttons outside the card.
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.getChildren().addAll(pauseBtn, resumeBtn, cancelBtn, openLinkBtn, retryBtn, folderBtn, clearBtn);

        textBox.getChildren().addAll(title, meta);
        // HBox children default to their content's minimum width. A title can be
        // arbitrarily long, so allow this column to shrink and let the labels
        // render an ellipsis inside the space that remains.
        textBox.setMinWidth(0);
        textBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getChildren().addAll(thumbBox, textBox, actions);

        footerRow.setAlignment(Pos.CENTER_LEFT);
        footerRow.setSpacing(2);

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        String metricStyle = "-fx-font-family: 'Menlo', 'Consolas', 'Monospaced'; -fx-font-size: 14px; -fx-text-fill: rgba(255,255,255,0.78);";
        sizeLabel.setStyle(metricStyle);
        speed.setStyle(metricStyle);
        eta.setStyle(metricStyle);
        status.setStyle(metricStyle);
        speedDot.setStyle(metricStyle);
        etaDot.setStyle(metricStyle);

        sizeLabel.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        speed.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        eta.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);

        sizeLabel.setMinWidth(155);
        sizeLabel.setPrefWidth(155);
        sizeLabel.setMaxWidth(155);
        sizeLabel.setAlignment(Pos.CENTER_RIGHT);
        sizeLabel.setTextOverrun(OverrunStyle.CLIP);

        speed.setMinWidth(85);
        speed.setPrefWidth(85);
        speed.setMaxWidth(85);
        speed.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        speed.setAlignment(Pos.CENTER_RIGHT);
        speed.setTextOverrun(OverrunStyle.CLIP);

        eta.setMinWidth(60);
        eta.setPrefWidth(60);
        eta.setMaxWidth(60);
        eta.setAlignment(Pos.CENTER_RIGHT);
        eta.setTextOverrun(OverrunStyle.CLIP);

        footerRow.getChildren().setAll(status, speedDot, speed, footerSpacer, sizeLabel, eta);

        card.getStyleClass().add("gx-task-card");
        card.setMinWidth(0);
        card.prefWidthProperty().bind(Bindings.max(0, widthProperty().subtract(2)));
        card.maxWidthProperty().bind(Bindings.max(0, widthProperty().subtract(2)));
        card.getChildren().addAll(headerRow, bar, footerRow);

        pauseBtn.setOnAction(e -> fire(onPause));
        resumeBtn.setOnAction(e -> fire(onResume));
        cancelBtn.setOnAction(e -> fire(onCancel));
        openLinkBtn.setOnAction(e -> fire(onOpenLink));
        folderBtn.setOnAction(e -> fire(onOpenFolder));
        retryBtn.setOnAction(e -> fire(onRetry));
        clearBtn.setOnAction(e -> fire(onClear));
    }

    public DownloadRowCell(
            Consumer<DownloadRow> onPause,
            Consumer<DownloadRow> onResume,
            Consumer<DownloadRow> onCancel,
            Consumer<DownloadRow> onOpenLink,
            Consumer<DownloadRow> onOpenFolder,
            Consumer<DownloadRow> onRetry,
            Consumer<DownloadRow> onClear,
            BiConsumer<Button, String> iconButtonSetup,
            BiConsumer<Button, String> tooltipInstaller,
            BiConsumer<javafx.scene.Node, javafx.beans.value.ObservableValue<String>> hoverTextInstaller,
            String pauseIcon,
            String resumeIcon,
            String cancelIcon,
            String openLinkIcon,
            String folderIcon,
            String retryIcon,
            String clearIcon
    ) {
        this(onPause, onResume, onCancel, onOpenLink, onOpenFolder, onRetry, onClear);
        this.iconButtonSetup = iconButtonSetup;
        this.tooltipInstaller = tooltipInstaller;
        this.hoverTextInstaller = hoverTextInstaller;
        if (this.hoverTextInstaller != null) {
            this.hoverTextInstaller.accept(title, title.textProperty());
        }
        applyButtonVisuals(pauseIcon, resumeIcon, cancelIcon, openLinkIcon, folderIcon, retryIcon, clearIcon);
    }


    public DownloadRowCell(
            Consumer<DownloadRow> onPause,
            Consumer<DownloadRow> onResume,
            Consumer<DownloadRow> onCancel,
            Consumer<DownloadRow> onOpenLink,
            Consumer<DownloadRow> onOpenFolder,
            Consumer<DownloadRow> onRetry,
            Consumer<DownloadRow> onClear
    ) {
        this();
        this.onPause = onPause;
        this.onResume = onResume;
        this.onCancel = onCancel;
        this.onOpenLink = onOpenLink;
        this.onOpenFolder = onOpenFolder;
        this.onRetry = onRetry;
        this.onClear = onClear;
    }

    @Override
    protected void updateItem(DownloadRow item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            detachRowListeners();
            unbindSmoothProgress();
            unbindRowProperties();
            unbindVisibilityRules();
            resetVisibilityRules();
            lastThumbUrl = null;
            thumb.setImage(null);
            thumb.setViewport(null);
            showThumbMessage("NO PREVIEW");
            setText(null);
            setGraphic(null);
            setPadding(Insets.EMPTY);
            return;
        }

        unbindRowProperties();
        detachRowListeners();
        setUserData(item);
        unbindVisibilityRules();
        resetVisibilityRules();

        title.textProperty().bind(item.title);
        status.textProperty().bind(item.status);
        speed.textProperty().bind(item.speed);
        eta.textProperty().bind(item.eta);
        sizeLabel.textProperty().bind(item.size);
        bindSmoothProgress(item);
        meta.setText(item.mode + " • " + item.quality + " • " + item.folder);

        if (stateListener == null) {
            stateListener = (obs, oldV, newV) ->
                    Platform.runLater(() -> applyButtonsForState(newV));
        }

        if (outputFileListener == null) {
            outputFileListener = (obs, oldV, newV) ->
                    Platform.runLater(() -> applyButtonsForState(currentState()));
        }

        try {
            if (item.state != null) {
                item.state.addListener(stateListener);
                applyButtonsForState(item.state.get());
            } else {
                applyButtonsForState(DownloadRow.State.QUEUED);
            }
        } catch (Exception ignored) {
            applyButtonsForState(DownloadRow.State.QUEUED);
        }

        try {
            if (item.outputFile != null) {
                item.outputFile.addListener(outputFileListener);
            }
        } catch (Exception ignored) {}

        if (thumbUrlListener == null) {
            thumbUrlListener = (obs, oldV, newV) -> loadThumbUrl(newV);
        }
        try {
            if (item.thumbUrl != null) {
                item.thumbUrl.addListener(thumbUrlListener);
                loadThumbUrl(item.thumbUrl.get());
            } else {
                loadThumbUrl(null);
            }
        } catch (Exception ignored) {
            loadThumbUrl(null);
        }

        applyVisibilityRules(item);
        clearBtn.setVisible(true);
        clearBtn.setManaged(true);

        setPadding(new Insets(10, 0, 10, 0));

        setText(null);
        setGraphic(card);
    }

    private void fire(Consumer<DownloadRow> action) {
        if (action == null) return;
        DownloadRow item = getItem();
        if (item == null) return;
        action.accept(item);
    }

    private void applyButtonsForState(DownloadRow.State st) {
        try {
            status.setStyle("");
        } catch (Exception ignored) {
        }
        if (st == null) st = DownloadRow.State.QUEUED;

        boolean isQueued = st == DownloadRow.State.QUEUED;
        boolean isDownloading = st == DownloadRow.State.DOWNLOADING;
        boolean isPaused = st == DownloadRow.State.PAUSED;
        boolean isCompleted = st == DownloadRow.State.COMPLETED;
        boolean isMissing = st == DownloadRow.State.MISSING;
        boolean isFailed = st == DownloadRow.State.FAILED || st == DownloadRow.State.CANCELLED;

        showButton(pauseBtn, false);
        showButton(resumeBtn, false);
        showButton(cancelBtn, false);
        showButton(openLinkBtn, false);
        showButton(retryBtn, false);
        showButton(folderBtn, true);
        folderBtn.setDisable(true);

        if (isDownloading) {
            showButton(pauseBtn, true);
            showButton(cancelBtn, true);
        } else if (isPaused) {
            showButton(resumeBtn, true);
            showButton(cancelBtn, true);
        } else if (isQueued) {
            showButton(cancelBtn, true);
        } else if (isMissing) {
            showButton(openLinkBtn, true);
            showButton(retryBtn, true);
            folderBtn.setDisable(true);
        } else if (isFailed) {
            showButton(retryBtn, true);
        } else if (isCompleted) {
            showButton(folderBtn, true);
            folderBtn.setDisable(!canOpenCompletedFile());
        }

        if (st == DownloadRow.State.FAILED) {
            try {
                status.setStyle("-fx-text-fill: #ff5b5b;");
            } catch (Exception ignored) {
            }
        }
    }

    private void showButton(Button button, boolean show) {
        if (button == null) return;
        button.setVisible(show);
        button.setManaged(show);
    }

    private DownloadRow.State currentState() {
        try {
            DownloadRow row = getItem();
            if (row != null && row.state != null && row.state.get() != null) {
                return row.state.get();
            }
        } catch (Exception ignored) {}
        return DownloadRow.State.QUEUED;
    }

    private boolean canOpenCompletedFile() {
        try {
            DownloadRow row = getItem();
            if (row == null) return false;
            if (row.state == null || row.state.get() != DownloadRow.State.COMPLETED) return false;
            if (row.outputFile == null || row.outputFile.get() == null) return false;

            java.nio.file.Path abs = row.outputFile.get().toAbsolutePath().normalize();
            return java.nio.file.Files.exists(abs);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void detachRowListeners() {
        try {
            DownloadRow previous = (DownloadRow) getUserData();

            if (previous != null && previous.state != null && stateListener != null) {
                previous.state.removeListener(stateListener);
            }

            if (previous != null && previous.outputFile != null && outputFileListener != null) {
                previous.outputFile.removeListener(outputFileListener);
            }

            if (previous != null && previous.thumbUrl != null && thumbUrlListener != null) {
                previous.thumbUrl.removeListener(thumbUrlListener);
            }
        } catch (Exception ignored) {
        }
        setUserData(null);
    }

    private void bindSmoothProgress(DownloadRow row) {
        try {
            if (progressBoundRow != null && progressListener != null) {
                progressBoundRow.progress.removeListener(progressListener);
            }
        } catch (Exception ignored) {
        }

        progressBoundRow = row;

        if (row == null) {
            targetProgress = 0.0;
            visualProgress = 0.0;
            bar.progressProperty().unbind();
            bar.setProgress(0);
            try {
                progressSmoother.stop();
            } catch (Exception ignored) {
            }
            return;
        }

        if (progressListener == null) {
            progressListener = (obs, oldV, newV) -> {
                if (newV == null) return;
                targetProgress = newV.doubleValue();
            };
        }

        try {
            row.progress.addListener(progressListener);
        } catch (Exception ignored) {
        }

        try {
            targetProgress = row.progress.get();
        } catch (Exception ignored) {
            targetProgress = 0.0;
        }

        if (targetProgress < 0) {
            bar.progressProperty().unbind();
            bar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        } else {
            visualProgress = clamp01(targetProgress);
            bar.progressProperty().unbind();
            bar.setProgress(visualProgress);
        }

        try {
            progressSmoother.start();
        } catch (Exception ignored) {
        }
    }

    private void unbindSmoothProgress() {
        try {
            if (progressBoundRow != null && progressListener != null) {
                progressBoundRow.progress.removeListener(progressListener);
            }
        } catch (Exception ignored) {
        }

        progressBoundRow = null;
        targetProgress = 0.0;
        visualProgress = 0.0;
        bar.progressProperty().unbind();
        bar.setProgress(0);

        try {
            progressSmoother.stop();
        } catch (Exception ignored) {
        }
    }

    private double clamp01(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }

    private void loadThumbUrl(String url) {
        try {
            if (url != null && url.equals(lastThumbUrl)) {
                return;
            }
            lastThumbUrl = url;

            if (url == null || url.isBlank()) {
                thumb.setImage(null);
                thumb.setViewport(null);
                showThumbMessage("NO PREVIEW");
                return;
            }

            Image cached = THUMB_IMAGE_CACHE.get(url);
            if (cached != null) {
                thumb.setImage(cached);
                if (cached.getException() != null) {
                    showThumbMessage("NO PREVIEW");
                } else if (cached.getProgress() >= 1.0 && cached.getWidth() > 0 && cached.getHeight() > 0) {
                    applyCoverViewport(thumb, cached, 108, 66);
                    showThumbImage();
                } else {
                    thumb.setViewport(null);
                    showThumbLoading();
                    watchThumbLoad(url, cached);
                }
                return;
            }

            Image image = new Image(url, true);
            THUMB_IMAGE_CACHE.put(url, image);
            thumb.setViewport(null);
            thumb.setImage(image);
            showThumbLoading();

            if (image.getWidth() > 0 && image.getHeight() > 0) {
                applyCoverViewport(thumb, image, 108, 66);
                showThumbImage();
            } else {
                watchThumbLoad(url, image);
            }
        } catch (Exception ignored) {
            thumb.setImage(null);
            thumb.setViewport(null);
            showThumbMessage("NO PREVIEW");
        }
    }

    private void watchThumbLoad(String url, Image image) {
        image.progressProperty().addListener((obs, oldV, newV) -> {
            if (newV == null || newV.doubleValue() < 1.0) return;
            Platform.runLater(() -> finishThumbLoad(url, image));
        });
        image.exceptionProperty().addListener((obs, oldError, error) -> {
            if (error != null) Platform.runLater(() -> finishThumbLoad(url, image));
        });
    }

    private void finishThumbLoad(String url, Image image) {
        if (url == null || !url.equals(lastThumbUrl) || thumb.getImage() != image) return;
        if (image.getException() != null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            showThumbMessage("NO PREVIEW");
            return;
        }
        applyCoverViewport(thumb, image, 108, 66);
        showThumbImage();
    }

    private void showThumbLoading() {
        thumbBox.getStyleClass().remove("gx-thumb-loaded");
        thumbPlaceholder.setText("•••");
        thumbPlaceholder.setVisible(true);
        if (thumbPulse.getStatus() != Animation.Status.RUNNING) {
            thumbPulse.playFromStart();
        }
    }

    private void showThumbImage() {
        if (!thumbBox.getStyleClass().contains("gx-thumb-loaded")) {
            thumbBox.getStyleClass().add("gx-thumb-loaded");
        }
        thumbPulse.stop();
        thumbPlaceholder.setOpacity(1.0);
        thumbPlaceholder.setVisible(false);
    }

    private void showThumbMessage(String message) {
        thumbBox.getStyleClass().remove("gx-thumb-loaded");
        thumbPulse.stop();
        thumbPlaceholder.setOpacity(1.0);
        thumbPlaceholder.setText(message);
        thumbPlaceholder.setVisible(true);
    }

    private void applyCoverViewport(ImageView view, Image image, double targetW, double targetH) {
        if (view == null || image == null) return;

        double imageW = image.getWidth();
        double imageH = image.getHeight();
        if (imageW <= 0 || imageH <= 0 || targetW <= 0 || targetH <= 0) return;

        double targetRatio = targetW / targetH;
        double imageRatio = imageW / imageH;

        double viewportW = imageW;
        double viewportH = imageH;
        double viewportX = 0;
        double viewportY = 0;

        if (imageRatio > targetRatio) {
            viewportW = imageH * targetRatio;
            viewportX = (imageW - viewportW) / 2.0;
        } else if (imageRatio < targetRatio) {
            viewportH = imageW / targetRatio;
            viewportY = (imageH - viewportH) / 2.0;
        }

        view.setViewport(new Rectangle2D(viewportX, viewportY, viewportW, viewportH));
        view.setFitWidth(targetW);
        view.setFitHeight(targetH);
        view.setPreserveRatio(false);
        view.setSmooth(true);
    }

    private void applyVisibilityRules(DownloadRow item) {
        if (item == null) return;

        try {
            BooleanBinding isDownloading = item.state.isEqualTo(DownloadRow.State.DOWNLOADING);

            BooleanBinding showSpeed = isDownloading
                    .and(item.speed.isNotNull())
                    .and(item.speed.isNotEmpty());

            BooleanBinding showEta = isDownloading
                    .and(item.eta.isNotNull())
                    .and(item.eta.isNotEmpty());

            BooleanBinding showSize = item.size.isNotNull()
                    .and(item.size.isNotEmpty())
                    .and(item.progress.greaterThanOrEqualTo(0));

            sizeLabel.visibleProperty().bind(showSize);
            sizeLabel.managedProperty().bind(showSize);

            speed.visibleProperty().bind(showSpeed);
            speed.managedProperty().bind(showSpeed);

            eta.visibleProperty().bind(showEta);
            eta.managedProperty().bind(showEta);

            speedDot.visibleProperty().bind(showSpeed);
            speedDot.managedProperty().bind(showSpeed);

            BooleanBinding showEtaDot = showEta.and(showSize);
            etaDot.visibleProperty().bind(showEtaDot);
            etaDot.managedProperty().bind(showEtaDot);

            status.setVisible(true);
            status.setManaged(true);
        } catch (Exception ignored) {
            resetVisibilityRules();
        }
    }

    private void unbindVisibilityRules() {
        try {
            sizeLabel.visibleProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            sizeLabel.managedProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            speed.visibleProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            speed.managedProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            eta.visibleProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            eta.managedProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            speedDot.visibleProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            speedDot.managedProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            etaDot.visibleProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            etaDot.managedProperty().unbind();
        } catch (Exception ignored) {
        }
    }

    private void resetVisibilityRules() {
        sizeLabel.setVisible(true);
        sizeLabel.setManaged(true);
        speed.setVisible(true);
        speed.setManaged(true);
        eta.setVisible(true);
        eta.setManaged(true);
        speedDot.setVisible(true);
        speedDot.setManaged(true);
        etaDot.setVisible(true);
        etaDot.setManaged(true);
        status.setVisible(true);
        status.setManaged(true);
    }

    private void unbindRowProperties() {
        try {
            title.textProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            status.textProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            speed.textProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            eta.textProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            sizeLabel.textProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            bar.progressProperty().unbind();
        } catch (Exception ignored) {
        }
    }

    private void setFallbackButtonText() {
        pauseBtn.setText("Pause");
        resumeBtn.setText("Resume");
        cancelBtn.setText("Cancel");
        openLinkBtn.setText("Link");
        folderBtn.setText("Folder");
        retryBtn.setText("Retry");
        clearBtn.setText("Clear");
    }

    private void applyButtonVisuals(
            String pauseIcon,
            String resumeIcon,
            String cancelIcon,
            String openLinkIcon,
            String folderIcon,
            String retryIcon,
            String clearIcon
    ) {
        setupIconButton(pauseBtn, pauseIcon, "Pause download");
        setupIconButton(resumeBtn, resumeIcon, "Resume download");
        setupIconButton(cancelBtn, cancelIcon, "Cancel download");
        setupIconButton(openLinkBtn, openLinkIcon, "Open link");
        setupIconButton(folderBtn, folderIcon, "Open folder");
        setupIconButton(retryBtn, retryIcon, "Retry download");
        setupIconButton(clearBtn, clearIcon, "Clear item");

        try {
            cancelBtn.getStyleClass().add("cancel");
        } catch (Exception ignored) {
        }
    }

    private void setupIconButton(Button button, String iconPath, String tooltipText) {
        if (button == null) return;

        if (iconButtonSetup != null && iconPath != null && !iconPath.isBlank()) {
            try {
                iconButtonSetup.accept(button, iconPath);
            } catch (Exception ignored) {
            }
        }

        if (tooltipInstaller != null && tooltipText != null && !tooltipText.isBlank()) {
            try {
                tooltipInstaller.accept(button, tooltipText);
            } catch (Exception ignored) {
            }
        }
    }
}
