package com.grabx.app.grabx.ui.playlist;

import com.grabx.app.grabx.util.VideoQualityUtils;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.geometry.Rectangle2D;
import javafx.util.Duration;

import java.util.List;

import static com.grabx.app.grabx.core.service.PlaylistDialogService.*;

public final class PlaylistEntryCell extends ListCell<PlaylistEntry> {
    public interface Context {
        String mode();
        String desiredQuality();
        boolean selectionUpdateInProgress();
        boolean canSelect(PlaylistEntry item);
        void selectionLimitReached();
        void selectionChanged(PlaylistEntry item, boolean selected);
        void qualityChanged(PlaylistEntry item, String quality);
        void visibleSelectedVideo(PlaylistEntry item, int index);
        boolean isSizeComputing(PlaylistEntry item, String quality);
        void qualityPopupChanged(boolean open);
        void titleHoverChanged(boolean hovering);
    }

    private static final int MAX_THUMB_CACHE_ENTRIES = 128;
    private static final java.util.Map<String, Image> THUMB_CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Image> eldest) {
                    return size() > MAX_THUMB_CACHE_ENTRIES;
                }
            });
    private static final java.util.Set<String> THUMB_INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ListView<PlaylistEntry> listView;
    private final Context context;
    private final double qualityWidth;

    private final CheckBox cb = new CheckBox();
    private final Label title = new Label();
    private final javafx.scene.control.Tooltip titleTip = new javafx.scene.control.Tooltip();
    private final PauseTransition titleTipThrottle = new PauseTransition(Duration.millis(45));
    private String lastTitleForTip = null;
    private final Label meta = new Label();

    private final VBox textBox = new VBox(4);

    private final StackPane thumbBox = new StackPane();
    private final ImageView thumb = new ImageView();
    private final Label placeholder = new Label("•••");
    private final FadeTransition thumbnailPulse = new FadeTransition(Duration.millis(850), placeholder);
    private Image watchedThumbnail;
    private ChangeListener<Number> watchedThumbnailProgress;
    private ChangeListener<Exception> watchedThumbnailException;

    private final ComboBox<String> qualityCombo = new ComboBox<>();
    private boolean updatingRowCombo = false;
    private boolean suppressQualityListener = false;
    private boolean suppressCheckListener = false;

    private final HBox card = new HBox(12);

    private boolean isChildOf(javafx.scene.Node n, javafx.scene.Node parent) {
        if (n == null || parent == null) return false;
        javafx.scene.Node cur = n;
        while (cur != null) {
            if (cur == parent) return true;
            cur = cur.getParent();
        }
        return false;
    }

    public PlaylistEntryCell(
            ListView<PlaylistEntry> listView, Context context, double qualityWidth
    ) {
        this.listView = listView;
        this.context = context;
        this.qualityWidth = qualityWidth;
        setStyle("-fx-background-color: transparent;");

        cb.getStyleClass().addAll("gx-check", "gx-playlist-check");
        cb.setFocusTraversable(false);

        thumb.setFitWidth(96);
        thumb.setFitHeight(54);
        thumb.setPreserveRatio(false);
        thumb.setSmooth(true);
        Rectangle thumbClip = new Rectangle(96, 54);
        thumbClip.setArcWidth(16);
        thumbClip.setArcHeight(16);
        thumb.setClip(thumbClip);

        placeholder.getStyleClass().add("gx-playlist-thumb-placeholder");

        thumbnailPulse.setFromValue(0.35);
        thumbnailPulse.setToValue(0.9);
        thumbnailPulse.setAutoReverse(true);
        thumbnailPulse.setCycleCount(Animation.INDEFINITE);

        thumbBox.getStyleClass().add("gx-playlist-thumb");
        thumbBox.getChildren().addAll(thumb, placeholder);

        // Use the same typography as the main list (keep playlist class too)
        title.getStyleClass().addAll("gx-task-title", "gx-playlist-title");
        // Force numbering to stay on the left even when the title contains RTL text
        title.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        title.setTextAlignment(javafx.scene.text.TextAlignment.LEFT);
        title.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        title.setWrapText(false);
        // prevent long titles from expanding the row/card; show ellipsis + tooltip
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        title.setPrefWidth(0);
        title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        titleTip.setWrapText(true);
        titleTip.setMaxWidth(520);

        // UX timing – feels native & light
        titleTip.setShowDelay(Duration.millis(300));
        titleTip.setHideDelay(Duration.millis(80));
        titleTip.setShowDuration(Duration.hours(1));

        Runnable refreshTitleTooltip = () -> {
            try {
                PlaylistEntry it = getItem();
                if (it == null || isEmpty()) {
                    title.setTooltip(null);
                    return;
                }
                // Only show tooltip when the visible label text is actually truncated.
                boolean truncated = isLabelTextTruncated(title);
                title.setTooltip(truncated ? titleTip : null);
            } catch (Exception ignored) {
                try { title.setTooltip(null); } catch (Exception ignored2) {}
            }
        };

        titleTipThrottle.setOnFinished(ev -> refreshTitleTooltip.run());

        // Re-evaluate when width changes (layout) or when text changes.
        title.widthProperty().addListener((o, a, b) -> {
            titleTipThrottle.stop();
            titleTipThrottle.playFromStart();
        });
        title.textProperty().addListener((o, a, b) -> {
            titleTipThrottle.stop();
            titleTipThrottle.playFromStart();
        });

        // Hold ListView refresh while the user is hovering the title (prevents tooltip from disappearing due to cell refresh)
        title.hoverProperty().addListener((o, was, isNow) -> context.titleHoverChanged(isNow));

        // Do NOT install tooltip here; we install/uninstall per-row only when truncated (see updateItem)

        meta.getStyleClass().addAll("gx-task-meta", "gx-playlist-meta");

        textBox.getChildren().addAll(title, meta);
        HBox.setHgrow(textBox, Priority.ALWAYS);
        textBox.setMinWidth(0);
        textBox.setMaxWidth(Double.MAX_VALUE);
        // Allow the VBox to shrink (so the row doesn't force horizontal expansion)
        textBox.setPrefWidth(0);

        qualityCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality", "gx-playlist-item-combo");
        qualityCombo.setPrefWidth(qualityWidth);
        qualityCombo.setMinWidth(qualityWidth);
        qualityCombo.setMaxWidth(240);
        qualityCombo.setDisable(true);

        qualityCombo.setCellFactory(x -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setDisable(QUALITY_SEPARATOR.equals(item));
                setOpacity(QUALITY_SEPARATOR.equals(item) ? 0.55 : 1.0);
            }
        });
        qualityCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });

        qualityCombo.showingProperty().addListener((obs, was, isNow) -> context.qualityPopupChanged(isNow));

        qualityCombo.valueProperty().addListener((obs, old, val) -> {
            if (updatingRowCombo || suppressQualityListener || val == null) return;
            if (QUALITY_SEPARATOR.equals(val) || QUALITY_CUSTOM.equals(val)) return;
            PlaylistEntry item = getItem();
            if (item == null || item.isUnavailable()) return;
            item.setQuality(val);
            meta.setText(buildMetaLine(item));
            context.qualityChanged(item, val);
        });

        card.getStyleClass().add("gx-playlist-card");
        card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        card.getChildren().addAll(cb, thumbBox, textBox, qualityCombo);
        // Force each cell/card to fit the ListView viewport width (prevents horizontal growth)
        setMaxWidth(Double.MAX_VALUE);
        prefWidthProperty().bind(listView.widthProperty().subtract(20));
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.prefWidthProperty().bind(prefWidthProperty());

        card.setOnMouseClicked(e -> {
            if (isEmpty() || getItem() == null) return;

            javafx.scene.Node target = (e.getTarget() instanceof javafx.scene.Node)
                    ? (javafx.scene.Node) e.getTarget()
                    : null;
            if (isChildOf(target, qualityCombo) || isChildOf(target, cb)) {
                return;
            }
            cb.setSelected(!cb.isSelected());
        });

        cb.selectedProperty().addListener((obs, was, isNow) -> {
            if (suppressCheckListener) return;
            PlaylistEntry item = getItem();
            if (item == null) return;
            if (item.isUnavailable()) {
                cb.setSelected(false);
                item.setSelected(false);
                return;
            }
            if (isNow && !context.selectionUpdateInProgress() && !context.canSelect(item)) {
                cb.setSelected(false);
                item.setSelected(false);
                syncCardSelectedStyle(item, card);
                context.selectionLimitReached();
                return;
            }
            item.setSelected(isNow);
            syncCardSelectedStyle(item, card);
            context.selectionChanged(item, isNow);
        });
    }

    @Override
    protected void updateItem(PlaylistEntry item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            // avoid stale tooltip on reused cells
            try { title.setTooltip(null); } catch (Exception ignored) {}
            stopWatchingThumbnail();
            thumbnailPulse.stop();
            setText(null);
            setGraphic(null);
            return;
        }

        // Prefix with LTR mark to keep "1." at the left edge for RTL titles
        String dt = item.displayTitle();
        if (dt == null) dt = "";
        title.setText("\u200E" + dt);

        // Keep full title in tooltip (but only attach tooltip when truncated)
        titleTip.setText(dt);
        // Force a re-check after the cell is laid out (virtualized list can report width=0 early)
        if (!dt.equals(lastTitleForTip)) {
            lastTitleForTip = dt;
        }
        title.setTooltip(null);
        titleTipThrottle.stop();
        titleTipThrottle.playFromStart();
        Platform.runLater(() -> {
            titleTipThrottle.stop();
            titleTipThrottle.playFromStart();
        });

        String itemQuality = item.getQuality();
        if (context.isSizeComputing(item, itemQuality)) {
            meta.setText((itemQuality == null ? "" : itemQuality) + " \u2022 ...");
        } else {
            meta.setText(buildMetaLine(item));
        }

        suppressCheckListener = true;
        try {
            cb.setSelected(item.isSelected());
        } finally {
            suppressCheckListener = false;
        }
        syncCardSelectedStyle(item, card);

        cb.setDisable(false);
        qualityCombo.setDisable(true); // الافتراضي معطّل
        // Show the combo, but keep it disabled until qualities are ready
        qualityCombo.setVisible(true);
        qualityCombo.setManaged(true);

        // ===== UNAVAILABLE =====
        if (item.isUnavailable()) {
            cb.setDisable(true);
            qualityCombo.setDisable(true);
            showThumbnailMessage("UNAVAILABLE");
            thumb.setImage(null);
            meta.setText("Unavailable");
            card.setOpacity(0.55);
            setGraphic(card);
            return;
        } else {
            card.setOpacity(1.0);
        }

        // ===== THUMBNAIL =====
        stopWatchingThumbnail();
        String tid = item.getId();
        Image cachedThumb = (tid == null) ? null : THUMB_CACHE.get(tid);

        if (cachedThumb != null) {
            thumb.setImage(cachedThumb);
            if (cachedThumb.getException() != null) {
                showThumbnailMessage("NO PREVIEW");
            } else if (cachedThumb.getProgress() >= 1.0) {
                applyCoverViewport(thumb, cachedThumb, 96, 54);
                showThumbnailImage();
            } else {
                showThumbnailLoading();
                watchThumbnailLoad(tid, cachedThumb);
            }

        } else if (item.getThumbUrl() != null && !item.getThumbUrl().isBlank()) {
            showThumbnailLoading();

            if (tid != null && THUMB_INFLIGHT.add(tid)) {
                Image img = new Image(item.getThumbUrl(), 192, 108, true, true, true);
                THUMB_CACHE.put(tid, img);

                img.progressProperty().addListener((o, oldP, newP) -> {
                    if (newP != null && newP.doubleValue() >= 1.0) {
                        THUMB_INFLIGHT.remove(tid);
                        Platform.runLater(() -> {
                                    PlaylistEntry now = getItem();
                                    if (now != null && tid.equals(now.getId()) && img.getException() == null) {
                                        applyCoverViewport(thumb, img, 96, 54);
                                        showThumbnailImage();
                                    }
                                });
                    }
                });

                img.exceptionProperty().addListener((o, oldEx, ex) -> {
                    THUMB_INFLIGHT.remove(tid);
                    if (ex != null) {
                        Platform.runLater(() -> {
                            PlaylistEntry now = getItem();
                            if (now != null && tid.equals(now.getId())) {
                                showThumbnailMessage("NO PREVIEW");
                            }
                        });
                    }
                });
            }

            thumb.setImage(THUMB_CACHE.get(tid));
        } else {
            thumb.setImage(null);
            showThumbnailMessage("NO PREVIEW");
        }

        // ===== MODE =====
        String modeNow = context.mode();
        if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

        // ===== AUDIO MODE =====
        if (MODE_AUDIO.equals(modeNow)) {
            updatingRowCombo = true;
            try {
                qualityCombo.getItems().setAll(buildAudioOptions());
                qualityCombo.setDisable(false);

                String cur = item.getQuality();
                if (cur == null || cur.isBlank() || QUALITY_BEST.equals(cur)) {
                    cur = context.desiredQuality();
                }
                if (cur == null || cur.isBlank()) cur = AUDIO_DEFAULT_FORMAT;

                suppressQualityListener = true;
                try {
                    qualityCombo.getSelectionModel().select(cur);
                } finally {
                    suppressQualityListener = false;
                }
            } finally {
                updatingRowCombo = false;
            }

            // Always visible in the row
            qualityCombo.setVisible(true);
            qualityCombo.setManaged(true);

            setGraphic(card);
            return;
        }

        // ===== VIDEO MODE – LOADING =====
        if (!item.isQualitiesLoaded()) {
            updatingRowCombo = true;
            try {
                qualityCombo.getItems().setAll("Loading qualities...");
                qualityCombo.setDisable(true);

                suppressQualityListener = true;
                try {
                    qualityCombo.getSelectionModel().select(0);
                } finally {
                    suppressQualityListener = false;
                }
            } finally {
                updatingRowCombo = false;
            }

            // Keep combo visible but disabled while probing
            qualityCombo.setVisible(true);
            qualityCombo.setManaged(true);

            if (item.isSelected()) {
                context.visibleSelectedVideo(item, getIndex());
            }

            setGraphic(card);
            return;
        }

        // ===== VIDEO MODE – READY =====
        java.util.List<String> q = item.getAvailableQualities();
        if (q != null && q.size() >= 2) {
            updatingRowCombo = true;
            try {
                qualityCombo.getItems().setAll(q);
                qualityCombo.setDisable(false);

                String cur = item.getQuality();
                if (cur == null || cur.isBlank()) cur = QUALITY_BEST;

                if (!q.contains(cur)) {
                    cur = VideoQualityUtils.closestSupportedLabel(cur, q, QUALITY_BEST, QUALITY_SEPARATOR);
                    item.setQuality(cur);
                }

                suppressQualityListener = true;
                try {
                    qualityCombo.getSelectionModel().select(cur);
                } finally {
                    suppressQualityListener = false;
                }
                // Always visible once ready
                qualityCombo.setVisible(true);
                qualityCombo.setManaged(true);
            } finally {
                updatingRowCombo = false;
            }
        } else {
            // fallback
            qualityCombo.setDisable(true);
            qualityCombo.setVisible(true);
            qualityCombo.setManaged(true);
        }

        setGraphic(card);
    }

    private void showThumbnailLoading() {
        thumbBox.getStyleClass().remove("gx-thumb-loaded");
        placeholder.setText("•••");
        placeholder.setVisible(true);
        if (thumbnailPulse.getStatus() != Animation.Status.RUNNING) {
            thumbnailPulse.playFromStart();
        }
    }

    private void watchThumbnailLoad(String thumbnailId, Image image) {
        if (thumbnailId == null || image == null) return;

        watchedThumbnail = image;
        watchedThumbnailProgress = (observable, oldProgress, newProgress) -> {
            if (newProgress != null && newProgress.doubleValue() >= 1.0) {
                Platform.runLater(() -> finishThumbnailLoad(thumbnailId, image));
            }
        };
        watchedThumbnailException = (observable, oldException, newException) -> {
            if (newException != null) {
                Platform.runLater(() -> finishThumbnailLoad(thumbnailId, image));
            }
        };
        image.progressProperty().addListener(watchedThumbnailProgress);
        image.exceptionProperty().addListener(watchedThumbnailException);
    }

    private void finishThumbnailLoad(String thumbnailId, Image image) {
        PlaylistEntry current = getItem();
        if (current == null || !thumbnailId.equals(current.getId()) || thumb.getImage() != image) return;

        stopWatchingThumbnail();
        if (image.getException() != null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            showThumbnailMessage("NO PREVIEW");
            return;
        }
        applyCoverViewport(thumb, image, 96, 54);
        showThumbnailImage();
    }

    private void stopWatchingThumbnail() {
        if (watchedThumbnail == null) return;
        if (watchedThumbnailProgress != null) {
            watchedThumbnail.progressProperty().removeListener(watchedThumbnailProgress);
        }
        if (watchedThumbnailException != null) {
            watchedThumbnail.exceptionProperty().removeListener(watchedThumbnailException);
        }
        watchedThumbnail = null;
        watchedThumbnailProgress = null;
        watchedThumbnailException = null;
    }

    private void showThumbnailImage() {
        if (!thumbBox.getStyleClass().contains("gx-thumb-loaded")) {
            thumbBox.getStyleClass().add("gx-thumb-loaded");
        }
        thumbnailPulse.stop();
        placeholder.setOpacity(1.0);
        placeholder.setVisible(false);
    }

    private void showThumbnailMessage(String message) {
        thumbBox.getStyleClass().remove("gx-thumb-loaded");
        thumbnailPulse.stop();
        placeholder.setOpacity(1.0);
        placeholder.setText(message);
        placeholder.setVisible(true);
    }

    private static void applyCoverViewport(
            ImageView view, Image image, double targetWidth, double targetHeight
    ) {
        if (view == null || image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return;

        double imageRatio = image.getWidth() / image.getHeight();
        double targetRatio = targetWidth / targetHeight;
        double viewportWidth = image.getWidth();
        double viewportHeight = image.getHeight();
        double viewportX = 0;
        double viewportY = 0;

        if (imageRatio > targetRatio) {
            viewportWidth = image.getHeight() * targetRatio;
            viewportX = (image.getWidth() - viewportWidth) / 2.0;
        } else if (imageRatio < targetRatio) {
            viewportHeight = image.getWidth() / targetRatio;
            viewportY = (image.getHeight() - viewportHeight) / 2.0;
        }

        view.setViewport(new Rectangle2D(viewportX, viewportY, viewportWidth, viewportHeight));
        view.setFitWidth(targetWidth);
        view.setFitHeight(targetHeight);
        view.setPreserveRatio(false);
    }

    private static String buildMetaLine(PlaylistEntry item) {
        if (item == null) return "";
        String quality = item.getQuality();
        String size = item.getSizeForQuality(quality);
        return size == null || size.isBlank() ? quality : quality + " \u2022 " + size;
    }

    private static List<String> buildAudioOptions() {
        return List.of(
                AUDIO_BEST, QUALITY_SEPARATOR, "m4a", "mp3", "opus", "aac", "wav", "flac"
        );
    }

    private static boolean isLabelTextTruncated(Label label) {
        if (label == null || label.getWidth() <= 1) return false;
        try {
            double available = label.getWidth() - label.getInsets().getLeft()
                    - label.getInsets().getRight() - 2.0;
            if (available <= 1) return false;
            String value = label.getText() == null ? "" : label.getText().replace("\u200E", "");
            javafx.scene.text.Text text = new javafx.scene.text.Text(value);
            text.setFont(label.getFont());
            return text.getLayoutBounds().getWidth() > available;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void syncCardSelectedStyle(PlaylistEntry item, Node card) {
        if (card == null) return;
        boolean selected = item != null && item.isSelected() && !item.isUnavailable();
        if (selected && !card.getStyleClass().contains("gx-selected")) {
            card.getStyleClass().add("gx-selected");
        } else if (!selected) {
            card.getStyleClass().remove("gx-selected");
        }
    }

}
