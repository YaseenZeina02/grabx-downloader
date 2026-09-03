package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.ui.components.NoSelectionModel;
import com.grabx.app.grabx.ui.playlist.PlaylistEntry;
import com.grabx.app.grabx.ui.playlist.PlaylistEntryCell;
import com.grabx.app.grabx.util.DownloadRuntimeUtils;
import com.grabx.app.grabx.util.VideoQualityUtils;
import com.grabx.app.grabx.util.YouTubeUrls;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;

public final class PlaylistDialogService {
    public static final String QUALITY_BEST = "Best quality (Recommended)";
    public static final String QUALITY_SEPARATOR = "──────────────";
    public static final String QUALITY_CUSTOM = "Mixed formats";
    public static final String MODE_VIDEO = "Video";
    public static final String MODE_AUDIO = "Audio only";
    public static final String AUDIO_BEST = "Best audio (Recommended)";
    public static final String AUDIO_DEFAULT_FORMAT = "mp3";
    public static final String VIDEO_DEFAULT_QUALITY = "480p";
    private static final List<String> AUDIO_FORMATS =
            List.of("m4a", "mp3", "opus", "aac", "wav", "flac");

    private final PlaylistProbeScheduler playlistProbeScheduler;
    private final VideoSizeService videoSizeService;

    public PlaylistDialogService(
            PlaylistProbeScheduler playlistProbeScheduler,
            VideoSizeService videoSizeService
    ) {
        this.playlistProbeScheduler = playlistProbeScheduler;
        this.videoSizeService = videoSizeService;
    }

    public enum Action { NONE, BACK, DOWNLOAD }

    public record Result(
            Action action,
            List<PlaylistEntry> batch,
            String mode,
            String quality,
            String folder
    ) {
        public static Result none() {
            return new Result(Action.NONE, List.of(), null, null, null);
        }

        public static Result back() {
            return new Result(Action.BACK, List.of(), null, null, null);
        }

        public static Result download(
                List<PlaylistEntry> batch, String mode, String quality, String folder
        ) {
            return new Result(Action.DOWNLOAD, List.copyOf(batch), mode, quality, folder);
        }
    }

    public Result show(javafx.stage.Window owner, String playlistUrl, String folder) {
        final long playlistProbeSession = playlistProbeScheduler.beginSession();
        final String playlistFolder = (folder == null || folder.isBlank())
                ? System.getProperty("user.home")
                : folder;

        java.util.concurrent.atomic.AtomicReference<Result> result =
                new java.util.concurrent.atomic.AtomicReference<>(Result.none());

        Stage stage = new Stage();
        stage.setTitle("Playlist");
        stage.setOnCloseRequest(event -> {
            if (result.get().action() == Action.NONE) result.set(Result.back());
        });
        stage.setOnHidden(event -> playlistProbeScheduler.cancelSession(playlistProbeSession));

        Button calcSize = new Button("Compute size");
        calcSize.getStyleClass().addAll("gx-btn", "gx-btn-ghost");
        calcSize.setDisable(true);

        // Lazy probing: visible/selected rows first, with a small look-ahead window.
        final java.util.Set<String> qualitiesInflight = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final int PLAYLIST_QUALITY_PREFETCH = 3;

        final double PLAYLIST_Q_COMBO_W = 160;
        final int PLAYLIST_MAX_SELECTED = 200; // hard cap to avoid UI/native crashes on huge playlists

        ObservableList<PlaylistEntry> items = FXCollections.observableArrayList();

        // Tracks size probes per item/quality so a row can show lightweight progress.
        final java.util.Set<String> playlistSizesInflight =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicLong sizeComputeRun =
                new java.util.concurrent.atomic.AtomicLong(0);
        final javafx.beans.property.BooleanProperty sizeBatchRunning =
                new javafx.beans.property.SimpleBooleanProperty(false);
        java.util.function.BiFunction<PlaylistEntry, String, String> sizeProbeKey = (it, quality) ->
                (it == null ? "" : String.valueOf(it.getId())) + "\u0000" + String.valueOf(quality);

        java.util.function.IntSupplier selectedCount = () -> {
            try {
                int c = 0;
                for (PlaylistEntry it : items) {
                    if (it != null && it.isSelected() && !it.isUnavailable()) c++;
                }
                return c;
            } catch (Exception ex) {
                return 0;
            }
        };

        try {
            if (owner != null) {
                stage.initOwner(owner);
                stage.initModality(javafx.stage.Modality.WINDOW_MODAL);
            } else {
                stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            }
        } catch (Exception ignored) {
            stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        }

        // لما تنعرض، خلّيها Key فورًا
        stage.setOnShown(ev -> Platform.runLater(() -> bringWindowToFront(stage)));

        VBox rootBox = new VBox(12);
        installClickToDefocus(rootBox);
        rootBox.getStyleClass().addAll("gx-panel", "gx-playlist-root");
        rootBox.setPadding(new Insets(16));
        rootBox.setFillWidth(true);

        // ✅ يمنع أي وميض/أبيض في بداية إظهار النافذة
        rootBox.setStyle("-fx-background-color: #121826;");

        Label header = new Label("Playlist items");
        header.getStyleClass().add("gx-section-title");

        Label sub = new Label("Select what you want to download, then Add & Start.");
        sub.getStyleClass().add("gx-text-muted");

        Label globalQLabel = new Label("Quality for all");
        globalQLabel.getStyleClass().add("gx-text-muted");

        Label globalModeLabel = new Label("Mode for all");
        globalModeLabel.getStyleClass().add("gx-text-muted");

        ComboBox<String> globalModeCombo = new ComboBox<>();
        globalModeCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality");
        globalModeCombo.getItems().setAll(MODE_VIDEO, MODE_AUDIO);

        ComboBox<String> globalQualityCombo = new ComboBox<>();
        globalQualityCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality");

        globalModeCombo.setPrefWidth(PLAYLIST_Q_COMBO_W);
        globalModeCombo.setMinWidth(PLAYLIST_Q_COMBO_W);

        globalQualityCombo.setPrefWidth(PLAYLIST_Q_COMBO_W);
        globalQualityCombo.setMinWidth(PLAYLIST_Q_COMBO_W);

        // standard list
        fillQualityCombo(globalQualityCombo);

        // separator behavior
        globalQualityCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                boolean mixedState = QUALITY_CUSTOM.equals(item);
                setText(empty || mixedState ? null : item);

                // Mixed formats is a display state for the closed combo, not a menu action.
                setVisible(!mixedState);
                setManaged(!mixedState);
                setMinHeight(mixedState ? 0 : Region.USE_COMPUTED_SIZE);
                setPrefHeight(mixedState ? 0 : Region.USE_COMPUTED_SIZE);
                setMaxHeight(mixedState ? 0 : Region.USE_COMPUTED_SIZE);

                boolean disabled = QUALITY_SEPARATOR.equals(item) || mixedState;
                setDisable(disabled);
                setOpacity(disabled ? 0.55 : 1.0);
            }
        });
        globalQualityCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });

        // store desired global mode/quality (used as default for items that are not manual)
        StringProperty globalDesiredMode = new SimpleStringProperty(MODE_VIDEO);
        StringProperty globalDesiredQuality = new SimpleStringProperty(VIDEO_DEFAULT_QUALITY);

        globalModeCombo.getSelectionModel().select(MODE_VIDEO);
        globalQualityCombo.getSelectionModel().select(VIDEO_DEFAULT_QUALITY);
        globalDesiredMode.set(MODE_VIDEO);
        globalDesiredQuality.set(VIDEO_DEFAULT_QUALITY);

        HBox globalRow = new HBox(10);
        globalRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        globalRow.getChildren().addAll(globalModeLabel, globalModeCombo, globalQLabel, globalQualityCombo);

        Label status = new Label("Loading playlist...");
        status.getStyleClass().add("gx-text-muted");

        ListView<PlaylistEntry> list = new ListView<>();
        list.getStyleClass().add("gx-playlist-list");
        list.setStyle("-fx-background-color: transparent;");

        Button download = new Button("Download");
        download.getStyleClass().addAll("gx-btn", "gx-btn-primary");
        download.setDisable(true);

        Runnable refreshAddState = () -> {
            try {
                boolean any = items.stream().anyMatch(it -> it != null && it.isSelected() && !it.isUnavailable());
                download.setDisable(!any);

                boolean anySizeMissing = items.stream().anyMatch(it -> {
                    if (it == null || !it.isSelected() || it.isUnavailable()) return false;

                    String quality = it.getQuality();
                    if (quality == null || quality.isBlank() || QUALITY_BEST.equals(quality)) {
                        quality = globalDesiredQuality.get();
                    }
                    if (quality == null || quality.isBlank()) quality = QUALITY_BEST;

                    String size = it.getSizeForQuality(quality);
                    boolean alreadyComputed = size != null && !size.isBlank();
                    return !alreadyComputed;
                });
                calcSize.setDisable(sizeBatchRunning.get() || !anySizeMissing);
            } catch (Exception ignored) {}
        };

        list.setItems(items);
        list.setPrefHeight(420);
        list.setFocusTraversable(false);
        list.setSelectionModel(new NoSelectionModel<>());

        // Throttle refreshes
        PauseTransition refreshThrottle = new PauseTransition(Duration.millis(140));
        javafx.beans.property.BooleanProperty anyQualityPopupOpen =
                new javafx.beans.property.SimpleBooleanProperty(false);
        // Hold ListView refresh while the user is hovering the title (prevents tooltip from disappearing due to cell refresh)
        javafx.beans.property.BooleanProperty anyTitleHoverHold =
                new javafx.beans.property.SimpleBooleanProperty(false);
        java.util.concurrent.atomic.AtomicBoolean pendingRefresh =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        java.util.concurrent.atomic.AtomicBoolean pendingMixedUpdate =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicReference<Runnable> updateGlobalMixedStateRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        Runnable requestRefreshSafe = () -> {
            if (anyQualityPopupOpen.get() || anyTitleHoverHold.get()) {
                pendingRefresh.set(true);
                return;
            }
            refreshThrottle.stop();
            refreshThrottle.setOnFinished(ev -> list.refresh());
            refreshThrottle.playFromStart();
        };

        java.util.function.Consumer<Boolean> maybeFlushPending = (ignored) -> {
            if (!anyQualityPopupOpen.get() && !anyTitleHoverHold.get()) {
                if (pendingRefresh.getAndSet(false)) {
                    refreshThrottle.stop();
                    refreshThrottle.setOnFinished(ev -> list.refresh());
                    refreshThrottle.playFromStart();
                }
                if (pendingMixedUpdate.getAndSet(false)) {
                    Platform.runLater(() -> {
                        Runnable r = updateGlobalMixedStateRef.get();
                        if (r != null) r.run();
                    });
                }
            }
        };

        globalQualityCombo.showingProperty().addListener((obs, was, isNow) -> anyQualityPopupOpen.set(isNow));
        anyQualityPopupOpen.addListener((o, was, isNow) -> { if (!isNow) maybeFlushPending.accept(false); });
        anyTitleHoverHold.addListener((o, was, isNow) -> { if (!isNow) maybeFlushPending.accept(false); });

        // ===== Union of discovered heights across playlist for the GLOBAL combo =====
        java.util.Set<Integer> globalHeightsUnion = new java.util.concurrent.ConcurrentSkipListSet<>();

        final javafx.beans.property.BooleanProperty updatingGlobalCombo =
                new javafx.beans.property.SimpleBooleanProperty(false);

        final javafx.beans.property.BooleanProperty updatingSelection =
                new javafx.beans.property.SimpleBooleanProperty(false);

        final Runnable updateGlobalMixedState = () -> {
            if (anyQualityPopupOpen.get() || updatingGlobalCombo.get()) {
                pendingMixedUpdate.set(true);
                return;
            }
            java.util.LinkedHashSet<String> selectedQualities = new java.util.LinkedHashSet<>();

            for (PlaylistEntry it : items) {
                if (it == null) continue;
                if (!it.isSelected()) continue;
                if (it.isUnavailable()) continue;
                String quality = it.getQuality();
                if (quality == null || quality.isBlank()) quality = globalDesiredQuality.get();
                if (quality == null || quality.isBlank()) quality = QUALITY_BEST;
                selectedQualities.add(quality);
            }
            updatingGlobalCombo.set(true);
            try {
                if (selectedQualities.isEmpty()) {
                    globalQualityCombo.getItems().remove(QUALITY_CUSTOM);
                    String desired = globalDesiredQuality.get();
                    if (desired == null || desired.isBlank()) desired = QUALITY_BEST;
                    globalQualityCombo.setValue(desired);
                } else if (selectedQualities.size() > 1) {
                    if (!globalQualityCombo.getItems().contains(QUALITY_CUSTOM)) {
                        globalQualityCombo.getItems().add(0, QUALITY_CUSTOM);
                    }
                    globalQualityCombo.getSelectionModel().select(QUALITY_CUSTOM);
                } else {
                    globalQualityCombo.getItems().remove(QUALITY_CUSTOM);
                    String commonQuality = selectedQualities.iterator().next();
                    if (globalQualityCombo.getItems().contains(commonQuality)) {
                        globalQualityCombo.getSelectionModel().select(commonQuality);
                    } else {
                        String mapped = VideoQualityUtils.closestSupportedLabel(commonQuality, new java.util.ArrayList<>(globalQualityCombo.getItems()), QUALITY_BEST, QUALITY_SEPARATOR);
                        globalQualityCombo.getSelectionModel().select(mapped);
                    }
                }
            } finally {
                updatingGlobalCombo.set(false);
            }
        };
        updateGlobalMixedStateRef.set(updateGlobalMixedState);

        PauseTransition globalComboUpdateThrottle = new PauseTransition(Duration.millis(220));
        Runnable updateGlobalQualityCombo = () -> {
            globalComboUpdateThrottle.stop();
            globalComboUpdateThrottle.setOnFinished(ev -> {
                if (MODE_AUDIO.equals(globalDesiredMode.get())) return;

                String prev = globalQualityCombo.getValue();
                if (prev == null || prev.isBlank()) prev = QUALITY_BEST;
                boolean keepCustom = QUALITY_CUSTOM.equals(prev);

                updatingGlobalCombo.set(true);
                try {
                    globalQualityCombo.getItems().clear();

                    if (keepCustom) globalQualityCombo.getItems().add(QUALITY_CUSTOM);

                    if (globalHeightsUnion.isEmpty()) {
                        globalQualityCombo.getItems().addAll(
                                QUALITY_BEST,
                                QUALITY_SEPARATOR,
                                "1080p", "720p", "480p", "360p", "240p", "144p"
                        );
                    } else {
                        java.util.Set<Integer> normalized = VideoQualityUtils.normalizeHeights(new java.util.HashSet<>(globalHeightsUnion));
                        java.util.List<Integer> sorted = new java.util.ArrayList<>(normalized);
                        sorted.sort(java.util.Comparator.reverseOrder());

                        globalQualityCombo.getItems().add(QUALITY_BEST);
                        globalQualityCombo.getItems().add(QUALITY_SEPARATOR);
                        for (Integer h : sorted) globalQualityCombo.getItems().add(VideoQualityUtils.formatHeightLabel(h));
                    }

                    if (QUALITY_CUSTOM.equals(prev) && keepCustom) {
                        globalQualityCombo.getSelectionModel().select(QUALITY_CUSTOM);
                    } else if (globalQualityCombo.getItems().contains(prev)) {
                        globalQualityCombo.getSelectionModel().select(prev);
                    } else {
                        String mapped = VideoQualityUtils.closestSupportedLabel(prev, new java.util.ArrayList<>(globalQualityCombo.getItems()), QUALITY_BEST, QUALITY_SEPARATOR);
                        globalQualityCombo.getSelectionModel().select(mapped);
                    }
                } finally {
                    updatingGlobalCombo.set(false);
                }

                Platform.runLater(() -> {
                    Runnable r = updateGlobalMixedStateRef.get();
                    if (r != null) r.run();
                });
            });
            globalComboUpdateThrottle.playFromStart();
        };

        // Global mode listener
        globalModeCombo.valueProperty().addListener((obs, old, val) -> {
            if (val == null) return;

            globalDesiredMode.set(val);

            updatingGlobalCombo.set(true);
            try {
                if (MODE_AUDIO.equals(val)) {
                    globalQualityCombo.getItems().setAll(buildAudioOptions());
                    globalQualityCombo.getSelectionModel().select(AUDIO_DEFAULT_FORMAT);
                    globalDesiredQuality.set(AUDIO_DEFAULT_FORMAT);
                } else {
                    updateGlobalQualityCombo.run();
                    globalQualityCombo.setValue(VIDEO_DEFAULT_QUALITY);
                    globalDesiredQuality.set(VIDEO_DEFAULT_QUALITY);
                }
            } finally {
                updatingGlobalCombo.set(false);
            }

            // Apply to ALL items (no size probing)
            for (PlaylistEntry it : items) {
                if (it == null || it.isUnavailable()) continue;
                it.setManualQuality(false);
                it.setQuality(MODE_AUDIO.equals(val)
                        ? globalDesiredQuality.get()
                        : VIDEO_DEFAULT_QUALITY);
            }

            requestRefreshSafe.run();
            refreshAddState.run();
            Platform.runLater(updateGlobalMixedState);
        });

        java.util.function.BiFunction<PlaylistEntry, PlaylistProbeScheduler.Priority, Boolean> requestQualityProbe =
                (it, priority) -> {
                    if (it == null || it.isUnavailable() || !it.isSelected() || it.isQualitiesLoaded()) return false;
                    String vid = it.getId();
                    if (vid == null || vid.isBlank()) return false;
                    if (!qualitiesInflight.add(vid)) {
                        return playlistProbeScheduler.promote(playlistProbeSession, vid, priority);
                    }

                    boolean queued = probeVideoQualitiesAsync(
                            playlistProbeSession,
                            YouTubeUrls.watchUrl(vid),
                            vid,
                            priority,
                            heights -> {
                                try {
                                    java.util.Set<Integer> norm = VideoQualityUtils.normalizeHeights(heights);
                                    if (norm != null && !norm.isEmpty()) globalHeightsUnion.addAll(norm);
                                    updateGlobalQualityCombo.run();

                                    java.util.ArrayList<String> labels = new java.util.ArrayList<>();
                                    labels.add(QUALITY_BEST);
                                    labels.add(QUALITY_SEPARATOR);
                                    java.util.List<Integer> sorted = norm == null
                                            ? new java.util.ArrayList<>()
                                            : new java.util.ArrayList<>(norm);
                                    sorted.sort(java.util.Comparator.reverseOrder());
                                    for (Integer height : sorted) labels.add(VideoQualityUtils.formatHeightLabel(height));
                                    it.setAvailableQualities(labels);

                                    if (it.getSizeByQuality() == null) it.setSizeByQuality(new java.util.HashMap<>());
                                    if (!MODE_AUDIO.equals(globalDesiredMode.get())) {
                                        String desired = it.isManualQuality() ? it.getQuality() : globalDesiredQuality.get();
                                        if (desired == null || desired.isBlank()) desired = QUALITY_BEST;
                                        it.setQuality(VideoQualityUtils.closestSupportedLabel(
                                                desired, it.getAvailableQualities(), QUALITY_BEST, QUALITY_SEPARATOR));
                                    }
                                    it.setQualitiesLoaded(true);
                                } catch (Exception ignored) {
                                    it.setQualitiesLoaded(false);
                                } finally {
                                    qualitiesInflight.remove(vid);
                                    requestRefreshSafe.run();
                                    refreshAddState.run();
                                }
                            }
                    );
                    if (!queued) qualitiesInflight.remove(vid);
                    return queued;
                };

        java.util.function.Consumer<PlaylistEntry> ensureProbed = it ->
                requestQualityProbe.apply(it, PlaylistProbeScheduler.Priority.SELECTED);

        java.util.function.IntConsumer prefetchAfter = visibleIndex -> {
            int scheduled = 0;
            for (int i = visibleIndex + 1; i < items.size() && scheduled < PLAYLIST_QUALITY_PREFETCH; i++) {
                PlaylistEntry candidate = items.get(i);
                if (candidate == null || candidate.isUnavailable() || !candidate.isSelected()
                        || candidate.isQualitiesLoaded()) continue;
                requestQualityProbe.apply(candidate, PlaylistProbeScheduler.Priority.PREFETCH);
                scheduled++;
            }
        };



        list.setCellFactory(lv -> new PlaylistEntryCell(
                lv,
                new PlaylistEntryCell.Context() {
                    @Override
                    public String mode() {
                        return globalDesiredMode.get();
                    }

                    @Override
                    public String desiredQuality() {
                        return globalDesiredQuality.get();
                    }

                    @Override
                    public boolean selectionUpdateInProgress() {
                        return updatingSelection.get();
                    }

                    @Override
                    public boolean canSelect(PlaylistEntry item) {
                        return selectedCount.getAsInt() < PLAYLIST_MAX_SELECTED;
                    }

                    @Override
                    public void selectionLimitReached() {
                        status.setText("Selection limit: " + PLAYLIST_MAX_SELECTED + " items");
                    }

                    @Override
                    public void selectionChanged(PlaylistEntry item, boolean selected) {
                        if (selected && !updatingSelection.get()) {
                            ensureProbed.accept(item);
                        } else if (!selected) {
                            String videoId = item.getId();
                            if (playlistProbeScheduler.cancelQueued(playlistProbeSession, videoId)) {
                                qualitiesInflight.remove(videoId);
                            }
                        }

                        if (!updatingSelection.get()) {
                            Platform.runLater(updateGlobalMixedState);
                        }
                        refreshAddState.run();
                    }

                    @Override
                    public void qualityChanged(PlaylistEntry item, String quality) {
                        String modeNow = globalDesiredMode.get();
                        if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

                        String desired = globalDesiredQuality.get();
                        if (desired == null || desired.isBlank()) desired = QUALITY_BEST;

                        String globalMapped;
                        if (MODE_AUDIO.equals(modeNow)) {
                            globalMapped = desired;
                        } else {
                            java.util.List<String> available = item.getAvailableQualities();
                            globalMapped = available == null || available.isEmpty()
                                    ? desired
                                    : VideoQualityUtils.closestSupportedLabel(
                                            desired, available, QUALITY_BEST, QUALITY_SEPARATOR);
                        }
                        item.setManualQuality(!quality.equals(globalMapped));
                        refreshAddState.run();
                        Platform.runLater(updateGlobalMixedState);
                    }

                    @Override
                    public void visibleSelectedVideo(PlaylistEntry item, int index) {
                        requestQualityProbe.apply(item, PlaylistProbeScheduler.Priority.VISIBLE);
                        prefetchAfter.accept(index);
                    }

                    @Override
                    public boolean isSizeComputing(PlaylistEntry item, String quality) {
                        String key = String.valueOf(globalDesiredMode.get()) + "\u0000"
                                + sizeProbeKey.apply(item, quality);
                        return playlistSizesInflight.contains(key);
                    }

                    @Override
                    public void qualityPopupChanged(boolean open) {
                        anyQualityPopupOpen.set(open);
                    }

                    @Override
                    public void titleHoverChanged(boolean hovering) {
                        anyTitleHoverHold.set(hovering);
                    }
                },
                PLAYLIST_Q_COMBO_W
        ));
        HBox actions = new HBox(10);
        actions.setPadding(new Insets(10, 0, 0, 0));

        Button selectAll = new Button("Select all");
        selectAll.getStyleClass().addAll("gx-btn", "gx-btn-ghost");

        Button clearSel = new Button("Clear");
        clearSel.getStyleClass().addAll("gx-btn", "gx-btn-ghost");


        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancel = new Button("Back");
        cancel.getStyleClass().addAll("gx-btn", "gx-btn-ghost");

        actions.getChildren().addAll(selectAll, clearSel, calcSize, spacer, cancel, download);
        rootBox.getChildren().addAll(header, sub, globalRow, list, status, actions);

        // slightly wider playlist window for long titles
        Scene scene = new Scene(rootBox, 920, 560);
        stage.setMinWidth(880);
        stage.setMinHeight(520);
        scene.setFill(Color.web("#121826"));
        scene.getRoot().setStyle("-fx-background-color: #121826;");

        scene.getStylesheets().addAll(
                getClass().getResource("/com/grabx/app/grabx/styles/theme-base.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/layout.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/buttons.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/sidebar.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/playlist.css").toExternalForm()
        );

        rootBox.applyCss();
        rootBox.layout();
        stage.setScene(scene);

        globalQualityCombo.valueProperty().addListener((obs, old, val) -> {
            if (val == null) return;
            if (QUALITY_SEPARATOR.equals(val)) return;
            if (QUALITY_CUSTOM.equals(val)) return;
            if (updatingGlobalCombo.get()) return;

            globalDesiredQuality.set(val);

            String modeNow = globalDesiredMode.get();
            if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

            for (PlaylistEntry it : items) {
                if (it == null || it.isUnavailable()) continue;

                it.setManualQuality(false);

                if (MODE_AUDIO.equals(modeNow)) {
                    it.setQuality(val);
                } else {
                    java.util.List<String> avail = it.getAvailableQualities();
                    String mapped = (avail == null || avail.isEmpty())
                            ? val
                            : VideoQualityUtils.closestSupportedLabel(val, avail, QUALITY_BEST, QUALITY_SEPARATOR);
                    it.setQuality(mapped);
                    // ✅ NO size probing
                }
            }

            requestRefreshSafe.run();
            refreshAddState.run();
            Platform.runLater(updateGlobalMixedState);
        });

        selectAll.setOnAction(e -> {
            updatingSelection.set(true);
            try {
                int c = 0;
                for (PlaylistEntry it : items) {
                    if (it == null || it.isUnavailable()) continue;
                    if (c >= PLAYLIST_MAX_SELECTED) {
                        it.setSelected(false);
                        continue;
                    }
                    it.setSelected(true);
                    c++;
                }
            } finally {
                updatingSelection.set(false);
            }
            try { if (status != null) status.setText("Selected up to " + PLAYLIST_MAX_SELECTED + " items"); } catch (Exception ignored) {}
            requestRefreshSafe.run();
            refreshAddState.run();
            Platform.runLater(updateGlobalMixedState);
            list.refresh();
            Platform.runLater(() -> {
                list.refresh();
                list.scrollTo(0);
            });
        });

        clearSel.setOnAction(e -> {
            updatingSelection.set(true);
            try {
                for (PlaylistEntry it : items) {
                    if (it == null) continue;
                    it.setSelected(false);
                    String videoId = it.getId();
                    if (playlistProbeScheduler.cancelQueued(playlistProbeSession, videoId)) {
                        qualitiesInflight.remove(videoId);
                    }
                }
            } finally {
                updatingSelection.set(false);
            }
            requestRefreshSafe.run();
            refreshAddState.run();
            Platform.runLater(updateGlobalMixedState);
        });
        calcSize.setOnAction(e -> {
            String modeNow = globalDesiredMode.get();
            if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

            long thisRun = sizeComputeRun.incrementAndGet();
            sizeBatchRunning.set(true);
            refreshAddState.run();
            int scheduled = 0;
            java.util.List<java.util.concurrent.CompletableFuture<Boolean>> probes = new java.util.ArrayList<>();
            for (PlaylistEntry it : items) {
                if (it == null || it.isUnavailable()) continue;
                if (!it.isSelected()) continue;

                String qNow = it.getQuality();
                if (qNow == null || qNow.isBlank() || QUALITY_BEST.equals(qNow)) {
                    qNow = globalDesiredQuality.get();
                }
                if (qNow == null || qNow.isBlank()) qNow = QUALITY_BEST;

                // تأكد إن الماب موجود
                try {
                    if (it.getSizeByQuality() == null) it.setSizeByQuality(new java.util.HashMap<>());
                } catch (Exception ignored) {}

                String probeKey = modeNow + "\u0000" + sizeProbeKey.apply(it, qNow);
                String existingSize = it.getSizeForQuality(qNow);
                if ((existingSize != null && !existingSize.isBlank())
                        || playlistSizesInflight.contains(probeKey)) {
                    continue;
                }

                playlistSizesInflight.add(probeKey);
                requestRefreshSafe.run();
                refreshAddState.run();

                java.util.concurrent.CompletableFuture<Boolean> probe =
                        ensurePlaylistSizeAsync(it, modeNow, qNow, requestRefreshSafe);
                probe.whenComplete((ok, error) -> Platform.runLater(() -> {
                    playlistSizesInflight.remove(probeKey);
                    requestRefreshSafe.run();
                    refreshAddState.run();
                }));
                probes.add(probe);
                scheduled++;
            }

            try {
                if (status != null) {
                    status.setText(scheduled > 0
                            ? ("Computing sizes for " + scheduled + " selected item(s)...")
                            : "No items selected.");
                }
            } catch (Exception ignored) {}

            if (!probes.isEmpty()) {
                java.util.concurrent.CompletableFuture
                        .allOf(probes.toArray(new java.util.concurrent.CompletableFuture[0]))
                        .whenComplete((ignored, error) -> Platform.runLater(() -> {
                            // A newer click owns the status text now.
                            if (sizeComputeRun.get() == thisRun) {
                                sizeBatchRunning.set(false);
                                refreshAddState.run();
                                if (status != null) status.setText("");
                            }
                        }));
            } else {
                sizeBatchRunning.set(false);
                refreshAddState.run();
            }
        });

        cancel.setOnAction(e -> {
            result.set(Result.back());
            stage.close();
        });

        download.setOnAction(e -> {
            java.util.List<PlaylistEntry> batch = items.stream()
                    .filter(it -> it != null && it.isSelected() && !it.isUnavailable())
                    .sorted(java.util.Comparator.comparingInt(PlaylistEntry::getIndex))
                    .toList();
            if (batch.isEmpty()) {
                status.setText("No items selected.");
                return;
            }

            result.set(Result.download(batch, globalDesiredMode.get(), globalDesiredQuality.get(), playlistFolder));
            stage.close();
        });

        // Load playlist entries asynchronously
        new Thread(() -> {
            java.util.List<PlaylistEntry> loaded;
            try {
                // Stream each entry into the list instead of waiting for yt-dlp to finish.
                loaded = new com.grabx.app.grabx.core.service.PlaylistService()
                        .loadFlatPlaylist(playlistUrl, entry -> {
                            entry.setSelected(false);
                            Platform.runLater(() -> {
                                if (!stage.isShowing()) return;
                                String currentDefault = globalDesiredQuality.get();
                                if (currentDefault == null || currentDefault.isBlank()) {
                                    currentDefault = MODE_AUDIO.equals(globalDesiredMode.get())
                                            ? AUDIO_DEFAULT_FORMAT
                                            : VIDEO_DEFAULT_QUALITY;
                                }
                                entry.setQuality(currentDefault);
                                items.add(entry);
                                status.setText("Loading playlist... " + items.size() + " item(s) found");
                                refreshAddState.run();
                            });
                        });
            } catch (Exception ignored) {
                loaded = java.util.List.of();
            }
            List<PlaylistEntry> finalLoaded = loaded;
            Platform.runLater(() -> {
                if (!stage.isShowing()) return;
                if (finalLoaded.isEmpty()) {
                    status.setText("Could not load playlist (yt-dlp missing?)");
                } else {
                    long bad = finalLoaded.stream().filter(PlaylistEntry::isUnavailable).count();
                    status.setText("Loaded " + finalLoaded.size() + " items" + (bad > 0 ? (" • " + bad + " unavailable") : ""));
                }

                requestRefreshSafe.run();
                refreshAddState.run();
            });
        }, "probe-playlist").start();

        stage.showAndWait();
        return result.get();
    }



    private boolean probeVideoQualitiesAsync(
            long session,
            String videoUrl,
            String videoId,
            PlaylistProbeScheduler.Priority priority,
            java.util.function.Consumer<java.util.Set<Integer>> onDone
    ) {
        return playlistProbeScheduler.request(session, videoId, videoUrl, priority, onDone);
    }

    private static java.util.List<String> buildAudioOptions() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        out.add(AUDIO_BEST);
        out.add(QUALITY_SEPARATOR);
        out.addAll(AUDIO_FORMATS);
        return out;
    }

    private static void fillQualityCombo(ComboBox<String> combo) {
        combo.getItems().setAll(
                QUALITY_BEST, QUALITY_SEPARATOR, "1080p", "720p", "540p",
                "480p", "360p", "240p", "144p"
        );
        combo.getSelectionModel().select(VIDEO_DEFAULT_QUALITY);
    }

    private java.util.concurrent.CompletableFuture<Boolean> ensurePlaylistSizeAsync(
            PlaylistEntry item, String mode, String quality, Runnable requestRefresh
    ) {
        if (item == null || item.isUnavailable() || quality == null || quality.isBlank()
                || QUALITY_SEPARATOR.equals(quality) || QUALITY_CUSTOM.equals(quality)) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        String videoUrl = YouTubeUrls.watchUrl(item.getId());
        if (videoUrl == null || videoUrl.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }

        Long cached = videoSizeService.getCached(videoUrl, mode, quality);
        if (cached != null && cached > 0) {
            putSize(item, quality, cached);
            if (requestRefresh != null) requestRefresh.run();
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
        String existing = item.getSizeForQuality(quality);
        if (existing != null && !existing.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }

        String selector;
        if (MODE_AUDIO.equals(mode)) {
            selector = "bestaudio/best";
        } else if (QUALITY_BEST.equals(quality)) {
            selector = "bv*+ba/b";
        } else {
            int height = VideoQualityUtils.parseHeight(quality);
            selector = height > 0
                    ? VideoQualityUtils.formatSelectorForHeight(height)
                    : "bv*+ba/b";
        }

        java.util.concurrent.CompletableFuture<Boolean> result =
                new java.util.concurrent.CompletableFuture<>();
        videoSizeService.probeAsync(videoUrl, mode, quality, selector)
                .whenComplete((bytes, error) -> Platform.runLater(() -> {
                    boolean success = error == null && bytes != null && bytes > 0;
                    if (success) {
                        putSize(item, quality, bytes);
                        if (requestRefresh != null) requestRefresh.run();
                    }
                    result.complete(success);
                }));
        return result;
    }

    private static void putSize(PlaylistEntry item, String quality, long bytes) {
        java.util.HashMap<String, String> sizes = new java.util.HashMap<>();
        if (item.getSizeByQuality() != null) sizes.putAll(item.getSizeByQuality());
        sizes.put(quality, DownloadRuntimeUtils.formatBytesDecimal(bytes));
        item.setSizeByQuality(sizes);
    }

    private static void installClickToDefocus(Node root) {
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            Scene scene = root.getScene();
            if (scene != null && scene.getFocusOwner() instanceof TextInputControl) {
                root.requestFocus();
            }
        });
    }

    private static void bringWindowToFront(javafx.stage.Window window) {
        if (window == null) return;
        window.requestFocus();
        if (window instanceof Stage stage) {
            stage.toFront();
            stage.requestFocus();
        }
    }
}
