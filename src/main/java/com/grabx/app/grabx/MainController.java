package com.grabx.app.grabx;
import com.grabx.app.grabx.ui.components.DownloadRowActions;
import com.grabx.app.grabx.ui.components.DownloadListViewService;
import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.core.service.DownloadStateCoordinator;
import com.grabx.app.grabx.core.service.DownloadHistoryReconciler;
import com.grabx.app.grabx.core.service.ClearAllService;
import com.grabx.app.grabx.core.service.PlaylistBatchCoordinator;
import com.grabx.app.grabx.core.service.PlaylistDialogService;
import com.grabx.app.grabx.core.service.ThumbnailService;
import com.grabx.app.grabx.core.service.UrlAnalysisService;
import com.grabx.app.grabx.core.service.FileManagerService;
import com.grabx.app.grabx.core.service.DownloadTitleService;
import com.grabx.app.grabx.core.service.DownloadProgressTracker;
import com.grabx.app.grabx.core.service.DownloadQueueService;
import com.grabx.app.grabx.core.service.AddLinkFlowService;
import com.grabx.app.grabx.core.service.SidebarService;
import com.grabx.app.grabx.core.service.DownloadRunner;
import com.grabx.app.grabx.core.service.DownloadFolderPreferences;
import com.grabx.app.grabx.core.service.VideoSizeService;
import com.grabx.app.grabx.core.service.PlaylistProbeScheduler;
import com.grabx.app.grabx.core.model.probe.VideoProbeService;
import com.grabx.app.grabx.util.AppLog;
import com.grabx.app.grabx.util.YouTubeUrls;
import com.grabx.app.grabx.util.VideoQualityUtils;
import com.grabx.app.grabx.util.DownloadRuntimeUtils;
import com.grabx.app.grabx.core.service.HoverTooltipService;

import java.util.*;
import java.util.concurrent.*;

import com.grabx.app.grabx.ui.components.ScrollbarAutoHide;
import com.grabx.app.grabx.ui.sidebar.SidebarItem;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;

import java.util.prefs.Preferences;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import java.util.logging.Logger;

public class MainController {
    private static final Logger LOG = AppLog.get(MainController.class);
    private final DownloadFolderPreferences downloadFolderPreferences = new DownloadFolderPreferences();
    private static final VideoProbeService VIDEO_PROBE_SERVICE = new VideoProbeService();
    private final VideoSizeService videoSizeService = new VideoSizeService();
    private final PlaylistProbeScheduler playlistProbeScheduler = new PlaylistProbeScheduler(VIDEO_PROBE_SERVICE);
    private final PlaylistDialogService playlistDialogService =
            new PlaylistDialogService(playlistProbeScheduler, videoSizeService);
    private final ThumbnailService thumbnailService = new ThumbnailService(UI_DELAY_EXEC);
    private final UrlAnalysisService urlAnalysisService = new UrlAnalysisService();
    private final FileManagerService fileManagerService =
            new FileManagerService(this::showMissingFileNotice);
    @FXML
    private TextField searchField;

    @FXML
    private Label statusText;
    @FXML
    private BorderPane root;

    @FXML
    private Button pauseAllButton;
    @FXML
    private Button resumeAllButton;
    @FXML
    private Button clearAllButton;

    @FXML
    private Button cancelAllBtn;

    @FXML
    private Button addLinkButton;
    @FXML
    private Button settingsButton;

    @FXML
    private Label contentTitle;
    @FXML
    private ListView<SidebarItem> sidebarList;

    @FXML
    private ListView<DownloadRow> downloadsList;


    // fields in MainController
    private PlaylistBatchCoordinator playlistBatchCoordinator;

    private com.grabx.app.grabx.core.service.AddLinkDialogService addLinkDialogService;
    private AddLinkFlowService addLinkFlowService;

    private HoverTooltipService hoverTooltipService;

    private final java.util.concurrent.atomic.AtomicLong downloadOrderSeq =
            new java.util.concurrent.atomic.AtomicLong(0);

    // فوق مع حقول الكلاس

    private com.grabx.app.grabx.core.service.ClipboardService clipboardService;
    private SidebarService sidebarService;


    private static final String ICON_PLUS =
            "M19 11H13V5h-2v6H5v2h6v6h2v-6h6v-2z";

    public static final String ICON_FOLDER_OPEN =
            "M3 6.5C3 5.12 4.12 4 5.5 4H10L12 6H18.5C19.88 6 21 7.12 21 8.5V17.5C21 18.88 19.88 20 18.5 20H5.5C4.12 20 3 18.88 3 17.5V6.5Z";

    public static final String ICON_PAUSE =
            "M6 5h4v14H6V5zm8 0h4v14h-4V5z";

    public static final String ICON_PLAY =
            "M8 5v14l11-7L8 5z";

    public static final String ICON_CANCEL =
            "M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.3 19.71 2.89 18.29 9.17 12 2.89 5.71 4.3 4.29 10.59 10.6 16.89 4.29z";

    public static final String ICON_RETRY =
            "M12 5a7 7 0 1 1-6.32 4H3l3.5-3.5L10 9H7.76A5.5 5.5 0 1 0 12 6.5V5z";

    public static final String ICON_CLEAR =
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";

    private static final String ICON_SETTINGS =
            "M19.14 12.94c.04-.31.06-.63.06-.94s-.02-.63-.06-.94l2.03-1.58" +
                    "c.18-.14.23-.41.12-.61l-1.92-3.32c-.11-.2-.36-.28-.57-.2l-2.39.96" +
                    "c-.5-.38-1.04-.69-1.64-.92l-.36-2.54c-.03-.22-.22-.38-.45-.38h-3.84" +
                    "c-.23 0-.42.16-.45.38l-.36 2.54c-.6.23-1.14.54-1.64.92l-2.39-.96" +
                    "c-.21-.08-.46 0-.57.2L2.71 8.89c-.11.2-.06.47.12.61l2.03 1.58" +
                    "c-.04.31-.06.63-.06.94s.02.63.06.94L2.83 14.54c-.18.14-.23.41-.12.61" +
                    "l1.92 3.32c.11.2.36.28.57.2l2.39-.96c.5.38 1.04.69 1.64.92l.36 2.54" +
                    "c.03.22.22.38.45.38h3.84c.23 0 .42-.16.45-.38l.36-2.54" +
                    "c.6-.23 1.14-.54 1.64-.92l2.39.96c.21.08.46 0 .57-.2l1.92-3.32" +
                    "c.11-.2.06-.47-.12-.61l-2.03-1.58z" +
                    "M12 15.5c-1.93 0-3.5-1.57-3.5-3.5S10.07 8.5 12 8.5" +
                    "s3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z";

    public static final String ICON_LINK=
            "M14 3h7v7h-2V6.41l-9.29 9.3-1.42-1.42 9.3-9.29H14V3z";



    private final java.util.Map<DownloadRow, Process> activeProcesses = new java.util.concurrent.ConcurrentHashMap<>();

    private DownloadStateCoordinator downloadStateCoordinator;
    private DownloadRunner downloadRunner;
    private final DownloadProgressTracker downloadProgressTracker = new DownloadProgressTracker();

    private final java.util.Map<DownloadRow, String> stopReasons = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String YTDLP_OUT_TMPL = "%(title)s.%(ext)s";


    private com.grabx.app.grabx.core.service.MissingWatcherService missingWatcherService;

    // Persist last selected download folder
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainController.class);

    private final ObservableList<DownloadRow> downloadItems = FXCollections.observableArrayList();

    //  ===========================
    //  ========= Classes =========
    //  ===========================
    private final com.grabx.app.grabx.core.service.DownloadService downloadService =
            new com.grabx.app.grabx.core.service.DownloadService(downloadItems);
    private final DownloadTitleService downloadTitleService =
            new DownloadTitleService(
                    downloadItems,
                    Platform::runLater,
                    text -> { if (statusText != null) statusText.setText(text); }
            );
    private final ClearAllService clearAllService = new ClearAllService(downloadItems);
    private final DownloadHistoryReconciler downloadHistoryReconciler =
            new DownloadHistoryReconciler(
                    downloadItems,
                    Platform::runLater,
                    downloadService::refilter,
                    this::updateMissingSidebarItem
            );

    private final com.grabx.app.grabx.core.service.HistoryService historyService =
            new com.grabx.app.grabx.core.service.HistoryService(
                    downloadItems,
                    downloadOrderSeq,
                    UI_DELAY_EXEC,
                    () -> {
                        try {
                            int v = PREFS.getInt("grabx.history.days", 30);
                            if (v < 1) v = 1;
                            if (v > 365) v = 365;
                            return v;
                        } catch (Exception ignored) {
                            return 30;
                        }
                    },
                    downloadHistoryReconciler::reconcileLoadedRows,
                    this::updateMissingSidebarItem,
                    thumbnailService::warmMissingAsync,
                    thumbnailService::thumbnailUrl
            );

    private DownloadQueueService downloadQueueService;


    //  ===========================


    // ========= In-scene hover tooltip (no jitter) =========

    public static final String QUALITY_BEST = "Best quality (Recommended)";
    public static final String QUALITY_SEPARATOR = "──────────────";
    private static final String MODE_VIDEO = "Video";
    public static final String MODE_AUDIO = "Audio only";

    public static final String AUDIO_BEST = "Best audio (Recommended)";
    public static final String AUDIO_DEFAULT_FORMAT = "mp3";
    private static final List<String> AUDIO_FORMATS = List.of(
            "m4a", "mp3", "opus", "aac", "wav", "flac"
    );

    // ========= Initialize =========

    @FXML
    public void initialize() {

        // Global modern scrollbar auto-hide
        Platform.runLater(() -> ScrollbarAutoHide.enableGlobalAutoHide(root));

        // remove initial focus from topbar buttons
        Platform.runLater(() -> {
            if (root != null) root.requestFocus();
        });

        installClickToDefocus(root);

        try {
            if (hoverTooltipService == null && root != null) {
                hoverTooltipService = new com.grabx.app.grabx.core.service.HoverTooltipService(root, MainController.class);
            }
        } catch (Exception ignored) {}

        try {
            if (hoverTooltipService != null) {
                hoverTooltipService.install(pauseAllButton, "Pause all");
                hoverTooltipService.install(resumeAllButton, "Resume all");
                hoverTooltipService.install(clearAllButton, "Clear all");
                hoverTooltipService.install(addLinkButton, "Add link");
                hoverTooltipService.install(settingsButton, "Settings");
                hoverTooltipService.install(cancelAllBtn, "Cancel All");
            }
        } catch (Exception ignored) {}

        setupSvgButton(addLinkButton, ICON_PLUS);
        setupSvgButton(pauseAllButton, ICON_PAUSE);
        setupSvgButton(resumeAllButton, ICON_PLAY);
        setupSvgButton(cancelAllBtn, ICON_CANCEL);
        setupSvgButton(clearAllButton, ICON_CLEAR);
        setupSvgButton(settingsButton, ICON_SETTINGS);

        // ✅ Make hover/press work on the whole Button (not only the icon node)

        normalizeIconButton(pauseAllButton);
        normalizeIconButton(resumeAllButton);
        normalizeIconButton(clearAllButton);
        normalizeIconButton(addLinkButton);
        normalizeIconButton(settingsButton);

        // Main downloads list will be initialized after DownloadStateCoordinator is ready.
        downloadStateCoordinator = new DownloadStateCoordinator(
                downloadItems,
                activeProcesses,
                stopReasons,
                (row, isResume) -> startDownloadRow(row, isResume),
                () -> {
                    try { updateMissingSidebarItem(); } catch (Exception ignored) {}
                    try {
                        if (downloadService != null) {
                            downloadService.setCombinedFilter(
                                    sidebarService.currentKey(),
                                    (searchField == null ? "" : searchField.getText())
                            );
                        }
                    } catch (Exception ignored) {}
                }
        );

        downloadRunner = new DownloadRunner(
                activeProcesses,
                stopReasons,
                downloadProgressTracker.progressByRow(),
                () -> {
                    try { if (historyService != null) historyService.scheduleSave(); } catch (Exception ignored) {}
                },
                this::updateMissingSidebarItem,
                () -> {
                    try {
                        if (downloadService != null) {
                            downloadService.setCombinedFilter(
                                    sidebarService.currentKey(),
                                    (searchField == null ? "" : searchField.getText())
                            );
                        }
                    } catch (Exception ignored) {}
                },
                label -> VideoQualityUtils.parseHeight(String.valueOf(label)),
                DownloadRuntimeUtils::probeOutputFilename,
                DownloadRuntimeUtils::supportsAudioThumbnailEmbedding,
                DownloadRuntimeUtils::isAudioStreamFromDestinationLine,
                DownloadRuntimeUtils::parseLongSafe,
                DownloadRuntimeUtils::formatBytesDecimal,
                DownloadRuntimeUtils::normalizeSpeedUnit,
                downloadProgressTracker::applyMonotonic,
                DownloadRuntimeUtils::killProcessTree,
                MODE_AUDIO,
                QUALITY_BEST,
                QUALITY_SEPARATOR,
                AUDIO_BEST,
                AUDIO_DEFAULT_FORMAT
        );

        downloadQueueService = new DownloadQueueService(
                downloadItems,
                downloadOrderSeq,
                downloadFolderPreferences::getLastFolderOrDefault,
                historyService::attachAutoSave,
                historyService::scheduleSave,
                thumbnailService::applyToRow,
                downloadTitleService::resolveAsync,
                this::startDownloadRow,
                Platform::runLater,
                text -> { if (statusText != null) statusText.setText(text); },
                MODE_VIDEO,
                MODE_AUDIO,
                QUALITY_BEST,
                AUDIO_DEFAULT_FORMAT
        );

        sidebarService = new SidebarService(
                sidebarList, contentTitle, statusText, searchField, downloadItems, downloadService
        );
        sidebarService.initialize();

        DownloadRowActions rowActions = new DownloadRowActions(
                downloadStateCoordinator,
                activeProcesses,
                stopReasons,
                downloadItems,
                statusText,
                this::startDownloadRow,
                this::updateMissingSidebarItem,
                sidebarService::refilter,
                fileManagerService::reveal
        );
        DownloadListViewService downloadListViewService = new DownloadListViewService(
                downloadsList,
                downloadService.view(),
                root,
                downloadStateCoordinator,
                rowActions,
                MainController::setupSvgButton,
                this::installTooltip,
                (node, text) -> hoverTooltipService.install(node, text),
                new DownloadListViewService.Icons(
                        ICON_PAUSE, ICON_PLAY, ICON_CANCEL, ICON_LINK,
                        ICON_FOLDER_OPEN, ICON_RETRY, ICON_CLEAR
                )
        );
        downloadListViewService.initialize();
        historyService.loadOnce();
        initPlaylistBatchCoordinator();


        try {
            if (addLinkDialogService == null && root != null) {
                addLinkDialogService =
                        new com.grabx.app.grabx.core.service.AddLinkDialogService(
                                root,
                                UI_DELAY_EXEC,
                                new com.grabx.app.grabx.core.service.AddLinkDialogService.Callbacks() {
                                    @Override public void installClickToDefocus(DialogPane pane) { MainController.this.installClickToDefocus(pane); }
                                    @Override public void bringWindowToFront(javafx.stage.Window w) { MainController.this.bringWindowToFront(w); }

                                    @Override public String shorten(String s) { return DownloadTitleService.shorten(s); }

                                    @Override public String getLastDownloadFolderOrDefault() { return downloadFolderPreferences.getLastFolderOrDefault(); }
                                    @Override public void saveLastDownloadFolder(String folder) { downloadFolderPreferences.saveLastFolder(folder); }

                                    @Override public void addDownloadItemToList(String url, String folder, String mode, String quality) {
                                        MainController.this.addDownloadItemToList(url, folder, mode, quality);
                                    }

                                    @Override public void onPlaylistDetected(String playlistUrl, String folder) {
                                        if (addLinkFlowService != null) addLinkFlowService.beginPlaylist(playlistUrl);
                                        MainController.this.openPlaylistWindow(playlistUrl, folder);
                                    }

                                    @Override public void setStatusText(String txt) {
                                        if (statusText != null && txt != null) statusText.setText(txt);
                                    }
                                },
                                new com.grabx.app.grabx.core.service.AddLinkDialogService.Config(
                                        MODE_VIDEO,
                                        MODE_AUDIO,
                                        QUALITY_BEST,
                                        QUALITY_SEPARATOR,
                                        AUDIO_DEFAULT_FORMAT,
                                        AUDIO_FORMATS,
                                        "/com/grabx/app/grabx/styles/theme-base.css",
                                        "/com/grabx/app/grabx/styles/layout.css",
                                        "/com/grabx/app/grabx/styles/buttons.css",
                                        "/com/grabx/app/grabx/styles/sidebar.css"
                                )
                        );
            }
        } catch (Exception ignored) {}

        if (addLinkDialogService != null) {
            addLinkFlowService = new AddLinkFlowService(
                    new AddLinkFlowService.DialogGateway() {
                        @Override public boolean isOpen() { return addLinkDialogService.isOpen(); }
                        @Override public void show(String prefillUrl) { addLinkDialogService.show(prefillUrl); }
                    },
                    urlAnalysisService::isHttpUrl,
                    com.grabx.app.grabx.core.service.ClipboardService::readClipboardTextSafe,
                    (action, delay) -> UI_DELAY_EXEC.schedule(action, delay, TimeUnit.MILLISECONDS),
                    Platform::runLater,
                    text -> { if (statusText != null) statusText.setText(text); }
            );
        }

        downloadItems.addListener((javafx.collections.ListChangeListener<DownloadRow>) c -> {
            boolean addedAny = false;
            while (c.next()) {
                if (c.wasAdded()) {
                    addedAny = true;
                    for (DownloadRow r : c.getAddedSubList()) {
                        if (r == null) continue;
                        historyService.attachAutoSave(r);  // ✅ يراقب تغييرات state/title/outputFile...الخ
                    }
                }
            }
            if (addedAny) {
                historyService.scheduleSave(); // ✅ حفظ سريع بعد إضافة عناصر جديدة
            }
        });

        updateMissingSidebarItem();

        // Missing watcher (init after all dependencies are ready)
        if (missingWatcherService == null) {
            missingWatcherService = new com.grabx.app.grabx.core.service.MissingWatcherService(
                    downloadItems,
                    UI_DELAY_EXEC,
                    () -> {
                        // refresh current view + sidebar when a completed file becomes missing
                        try {
                            downloadService.setCombinedFilter(
                                    sidebarService.currentKey(),
                                    (searchField == null ? "" : searchField.getText())
                            );
                        } catch (Exception ignored) {}

                        try {
                            Platform.runLater(this::updateMissingSidebarItem);
                        } catch (Exception ignored) {}
                    }
            );
        }
        try { missingWatcherService.start(); } catch (Exception ignored) {}

        try {
            clipboardService = new com.grabx.app.grabx.core.service.ClipboardService(
                    root,
                    addLinkFlowService == null ? url -> {} : addLinkFlowService::openOrUpdate,
                    urlAnalysisService::isHttpUrl
            );
            clipboardService.start();
        } catch (Exception ignored) {}
        // + button: open Add Link and prefill from clipboard if URL
        if (addLinkButton != null) {
            addLinkButton.setOnAction(ev -> {
                if (addLinkFlowService != null) addLinkFlowService.showFromClipboardDeferred();
            });
        }
    }

    private void initPlaylistBatchCoordinator() {
        try {
            playlistBatchCoordinator = new PlaylistBatchCoordinator(
                    downloadQueueService::create,
                    downloadItems::add,
                    thumbnailService::applyToRow,
                    historyService::attachAutoSave,
                    historyService::scheduleSave,
                    row -> startDownloadRow(row, false),
                    sidebarService::refilter,
                    Platform::runLater,
                    YouTubeUrls::watchUrl,
                    YouTubeUrls::extractVideoId,
                    text -> { if (statusText != null && text != null) statusText.setText(text); }
            );
        } catch (Exception ex) {
            playlistBatchCoordinator = null;
        }
    }

    // ========= Actions =========
    @FXML
    public void onAddLink(ActionEvent event) {
        if (addLinkFlowService != null) addLinkFlowService.showFromClipboard();
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
    public void onCanseleAll(ActionEvent e) {
        int affected = (downloadStateCoordinator == null) ? 0 : downloadStateCoordinator.cancelAll();
        if (statusText != null) statusText.setText(affected > 0 ? ("Cancelled " + affected + " item(s)") : "Nothing to cancel");
    }

    @FXML
    public void onPauseAll(ActionEvent e) {
        int affected = (downloadStateCoordinator == null) ? 0 : downloadStateCoordinator.pauseAll();
        if (statusText != null) statusText.setText(affected > 0 ? ("Paused " + affected + " item(s)") : "Nothing to pause");
    }

    @FXML
    public void onResumeAll(ActionEvent e) {
        int affected = (downloadStateCoordinator == null) ? 0 : downloadStateCoordinator.resumeAll();
        if (statusText != null) statusText.setText(affected > 0 ? ("Resumed " + affected + " item(s)") : "Nothing to resume");
    }

    @FXML
    public void onClearAll(ActionEvent actionEvent) {
        int removed = (clearAllService == null) ? 0 : clearAllService.clearNonActive();

        if (removed > 0) {
            updateMissingSidebarItem();

            // keep current sidebar/search filter consistent
            try {
                downloadService.setCombinedFilter(
                        sidebarService.currentKey(),
                        (searchField == null ? "" : searchField.getText())
                );
            } catch (Exception ignored) {}

            // ✅ مهم: ثبت التغيير على ملف الهيستوري
            try {
                if (historyService != null) {
                    if (downloadItems == null || downloadItems.isEmpty()) {
                        historyService.clearHistoryFile();  // <- جديد
                    } else {
                        historyService.scheduleSave();
                    }
                }
            } catch (Exception ignored) {}
        }

        if (statusText != null) {
            statusText.setText(removed == 0 ? "Nothing to clear" : ("Cleared " + removed + " item(s)"));
        }
    }



    // ========= AddLink open helpers (safe showAndWait) =========

    // Small delay helper (avoids calling showAndWait from animation/layout pulses)
    private static final ScheduledExecutorService UI_DELAY_EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-delay");
        t.setDaemon(true);
        return t;
    });

    // ========= Fix icon buttons hover/press =========

    private void normalizeIconButton(Button btn) {
        if (btn == null) return;

        // Make the entire bounds clickable
        btn.setPickOnBounds(true);

        // VERY IMPORTANT: let the Button receive mouse events (icon should not steal them)
        Node g = btn.getGraphic();
        if (g != null) {
            g.setMouseTransparent(true);
        }
    }





    // ========= Custom in-scene tooltip bubble (no Popup/Tooltip jitter) =========


    private void updateMissingSidebarItem() {
        if (sidebarService != null) sidebarService.refreshMissingItem();
    }

    public static Node svgIcon(String path, double boxSize) {
        javafx.scene.shape.SVGPath svg = new javafx.scene.shape.SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("gx-svg-icon");

        StackPane box = new StackPane(svg);
        box.setMinSize(boxSize, boxSize);
        box.setPrefSize(boxSize, boxSize);
        box.setMaxSize(boxSize, boxSize);

        // Scale to fit nicely
        Platform.runLater(() -> {
            var b = svg.getBoundsInLocal();
            double iw = b.getWidth(), ih = b.getHeight();
            if (iw <= 0 || ih <= 0) return;
            double target = boxSize * 0.52;
            double s = Math.min(target / iw, target / ih);
            svg.setScaleX(s);
            svg.setScaleY(s);
        });

        return box;
    }

    public static void setupSvgButton(Button b, String svgPath) {
        // Match Topbar icon buttons look
        b.getStyleClass().addAll("gx-icon-btn", "gx-task-action");
        b.setFocusTraversable(false);
        b.setText(null);
        b.setGraphic(svgIcon(svgPath, 34));

    }


    /** Backwards-compatible helper so existing code can keep calling installTooltip(...) */
    private void installTooltip(javafx.scene.control.Button btn, String text) {
        try {
            if (hoverTooltipService != null) {
                hoverTooltipService.install(btn, text);
            }
        } catch (Exception ignored) {}
    }

    private void addDownloadItemToList(String url, String folder, String mode, String quality) {
        downloadQueueService.enqueue(url, folder, mode, quality);
    }

    // Only keep the version with yt-dlp --progress-template and regex patterns DEST1, DEST2, MERGE, PROG, etc.

    private void startDownloadRow(DownloadRow row, boolean resume) {
        downloadRunner.start(row, resume);
    }

    private void showMissingFileNotice() {
        try {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("File not found");
            alert.setHeaderText("This file is no longer in its original location.");
            alert.setContentText("It looks like the file was moved, renamed, or deleted.");
            alert.show();
        } catch (Exception ignored) {
        }
    }


    // Playlist UI and its local state live in PlaylistDialogService.
    private void openPlaylistWindow(String playlistUrl, String folder) {
        String playlistFolder = (folder == null || folder.isBlank())
                ? downloadFolderPreferences.getLastFolderOrDefault()
                : folder;

        javafx.stage.Window owner = null;
        try {
            if (root != null && root.getScene() != null) {
                owner = root.getScene().getWindow();
            }
        } catch (Exception ignored) {}

        PlaylistDialogService.Result result =
                playlistDialogService.show(owner, playlistUrl, playlistFolder);
        if (result == null) return;

        if (result.action() == PlaylistDialogService.Action.DOWNLOAD) {
            downloadFolderPreferences.saveLastFolder(result.folder());
            if (playlistBatchCoordinator != null) {
                playlistBatchCoordinator.enqueue(result.batch(), result.mode(), result.quality());
            } else {
                if (statusText != null) statusText.setText("Playlist download service is unavailable.");
                return;
            }

            if (statusText != null) {
                statusText.setText("Queued playlist: " + result.batch().size() + " items");
            }
            if (addLinkFlowService != null) addLinkFlowService.completePlaylist();
            return;
        }

        if (result.action() == PlaylistDialogService.Action.BACK && addLinkFlowService != null) {
            addLinkFlowService.returnFromPlaylist();
        }
    }
    // UX: prevent “first click just removes focus” feeling
    private static void installClickToDefocus(Node rootNode) {
        if (rootNode == null) return;

        rootNode.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            try {
                Scene sc = rootNode.getScene();
                if (sc == null) return;

                Node fo = sc.getFocusOwner();
                if (fo instanceof TextInputControl) {
                    // remove focus from the text input but DON'T consume the click
                    rootNode.requestFocus();
                }
            } catch (Exception ignored) {}
        });
    }

    private static void bringWindowToFront(javafx.stage.Window w) {
        if (w == null) return;
        try {
            w.requestFocus();
            if (w instanceof javafx.stage.Stage s) {
                s.toFront();
                s.requestFocus();
            }
        } catch (Exception ignored) {}
    }



}
