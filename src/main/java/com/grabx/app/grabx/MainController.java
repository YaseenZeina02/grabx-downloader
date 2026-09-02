package com.grabx.app.grabx;
import com.grabx.app.grabx.ui.components.DownloadRowActions;
import com.grabx.app.grabx.ui.components.DownloadListViewService;
import com.grabx.app.grabx.ui.components.IconButtonService;
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
import com.grabx.app.grabx.core.service.BulkDownloadActionsService;
import com.grabx.app.grabx.core.service.AddLinkDialogFactory;
import com.grabx.app.grabx.core.service.AddLinkDialogService;
import com.grabx.app.grabx.core.service.DownloadEngineFactory;
import com.grabx.app.grabx.core.service.SidebarService;
import com.grabx.app.grabx.core.service.DownloadRunner;
import com.grabx.app.grabx.core.service.DownloadFolderPreferences;
import com.grabx.app.grabx.core.service.VideoSizeService;
import com.grabx.app.grabx.core.service.PlaylistProbeScheduler;
import com.grabx.app.grabx.core.model.probe.VideoProbeService;
import com.grabx.app.grabx.util.YouTubeUrls;
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
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class MainController {
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

    private AddLinkDialogService addLinkDialogService;
    private AddLinkFlowService addLinkFlowService;

    private HoverTooltipService hoverTooltipService;

    private final java.util.concurrent.atomic.AtomicLong downloadOrderSeq =
            new java.util.concurrent.atomic.AtomicLong(0);

    // فوق مع حقول الكلاس

    private com.grabx.app.grabx.core.service.ClipboardService clipboardService;
    private SidebarService sidebarService;


    private final java.util.Map<DownloadRow, Process> activeProcesses = new java.util.concurrent.ConcurrentHashMap<>();

    private DownloadStateCoordinator downloadStateCoordinator;
    private DownloadRunner downloadRunner;
    private final DownloadProgressTracker downloadProgressTracker = new DownloadProgressTracker();

    private final java.util.Map<DownloadRow, String> stopReasons = new java.util.concurrent.ConcurrentHashMap<>();
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
    private BulkDownloadActionsService bulkDownloadActionsService;


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

        AddLinkDialogFactory.installClickToDefocus(root);

        try {
            if (hoverTooltipService == null && root != null) {
                hoverTooltipService = new com.grabx.app.grabx.core.service.HoverTooltipService(root, MainController.class);
            }
        } catch (Exception ignored) {}

        IconButtonService iconButtons = new IconButtonService(hoverTooltipService);
        iconButtons.initializeToolbar(
                addLinkButton, pauseAllButton, resumeAllButton,
                cancelAllBtn, clearAllButton, settingsButton
        );

        sidebarService = new SidebarService(
                sidebarList, contentTitle, statusText, searchField, downloadItems, downloadService
        );
        sidebarService.initialize();

        DownloadEngineFactory.Runtime downloadRuntime = DownloadEngineFactory.create(
                downloadItems,
                activeProcesses,
                stopReasons,
                downloadProgressTracker,
                this::startDownloadRow,
                historyService::scheduleSave,
                this::updateMissingSidebarItem,
                sidebarService::refilter,
                new DownloadEngineFactory.Config(
                        MODE_AUDIO, QUALITY_BEST, QUALITY_SEPARATOR, AUDIO_BEST, AUDIO_DEFAULT_FORMAT
                )
        );
        downloadStateCoordinator = downloadRuntime.stateCoordinator();
        downloadRunner = downloadRuntime.runner();

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

        bulkDownloadActionsService = new BulkDownloadActionsService(
                downloadStateCoordinator::cancelAll,
                downloadStateCoordinator::pauseAll,
                downloadStateCoordinator::resumeAll,
                clearAllService::clearNonActive,
                downloadItems::isEmpty,
                this::updateMissingSidebarItem,
                sidebarService::refilter,
                historyService::clearHistoryFile,
                historyService::scheduleSave,
                text -> { if (statusText != null) statusText.setText(text); }
        );

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
                IconButtonService::setupSvgButton,
                iconButtons::installTooltip,
                (node, text) -> hoverTooltipService.install(node, text),
                IconButtonService.downloadIcons()
        );
        downloadListViewService.initialize();
        historyService.loadOnce();
        initPlaylistBatchCoordinator();


        try {
            addLinkDialogService = AddLinkDialogFactory.create(
                    root,
                    UI_DELAY_EXEC,
                    downloadFolderPreferences::getLastFolderOrDefault,
                    downloadFolderPreferences::saveLastFolder,
                    downloadQueueService::enqueue,
                    (playlistUrl, folder) -> {
                        if (addLinkFlowService != null) addLinkFlowService.beginPlaylist(playlistUrl);
                        openPlaylistWindow(playlistUrl, folder);
                    },
                    text -> { if (statusText != null) statusText.setText(text); },
                    AddLinkDialogFactory.defaultConfig(
                            MODE_VIDEO, MODE_AUDIO, QUALITY_BEST, QUALITY_SEPARATOR,
                            AUDIO_DEFAULT_FORMAT, AUDIO_FORMATS
                    )
            );
        } catch (Exception ignored) {
            addLinkDialogService = null;
        }

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
        if (bulkDownloadActionsService != null) bulkDownloadActionsService.cancelAll();
    }

    @FXML
    public void onPauseAll(ActionEvent e) {
        if (bulkDownloadActionsService != null) bulkDownloadActionsService.pauseAll();
    }

    @FXML
    public void onResumeAll(ActionEvent e) {
        if (bulkDownloadActionsService != null) bulkDownloadActionsService.resumeAll();
    }

    @FXML
    public void onClearAll(ActionEvent actionEvent) {
        if (bulkDownloadActionsService != null) bulkDownloadActionsService.clearAll();
    }



    // ========= AddLink open helpers (safe showAndWait) =========

    // Small delay helper (avoids calling showAndWait from animation/layout pulses)
    private static final ScheduledExecutorService UI_DELAY_EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-delay");
        t.setDaemon(true);
        return t;
    });

    private void updateMissingSidebarItem() {
        if (sidebarService != null) sidebarService.refreshMissingItem();
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
}
