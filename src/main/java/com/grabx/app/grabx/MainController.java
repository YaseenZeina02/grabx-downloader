package com.grabx.app.grabx;
import com.grabx.app.grabx.ui.components.DownloadRowActions;
import com.grabx.app.grabx.ui.components.DownloadListViewService;
import com.grabx.app.grabx.ui.components.IconButtonService;
import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.core.service.DownloadStateCoordinator;
import com.grabx.app.grabx.core.service.DownloadHistoryReconciler;
import com.grabx.app.grabx.core.service.PlaylistDialogService;
import com.grabx.app.grabx.core.service.PlaylistFlowService;
import com.grabx.app.grabx.core.service.PlaylistServicesFactory;
import com.grabx.app.grabx.core.service.ThumbnailService;
import com.grabx.app.grabx.core.service.UrlAnalysisService;
import com.grabx.app.grabx.core.service.FileManagerService;
import com.grabx.app.grabx.core.service.DownloadTitleService;
import com.grabx.app.grabx.core.service.DownloadQueueService;
import com.grabx.app.grabx.core.service.AddLinkFlowService;
import com.grabx.app.grabx.core.service.BulkDownloadActionsService;
import com.grabx.app.grabx.core.service.AddLinkDialogFactory;
import com.grabx.app.grabx.core.service.AddLinkServicesFactory;
import com.grabx.app.grabx.core.service.DownloadServicesFactory;
import com.grabx.app.grabx.core.service.DownloadMonitoringService;
import com.grabx.app.grabx.core.service.SidebarService;
import com.grabx.app.grabx.core.service.DownloadRunner;
import com.grabx.app.grabx.core.service.DownloadFolderPreferences;
import com.grabx.app.grabx.core.service.DownloadService;
import com.grabx.app.grabx.core.service.HistoryService;
import com.grabx.app.grabx.core.service.GlobalSpeedService;
import com.grabx.app.grabx.core.service.ClipboardService;
import com.grabx.app.grabx.core.service.VideoSizeService;
import com.grabx.app.grabx.core.service.PlaylistProbeScheduler;
import com.grabx.app.grabx.core.model.probe.VideoProbeService;
import com.grabx.app.grabx.core.service.HoverTooltipService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import com.grabx.app.grabx.ui.components.ScrollbarAutoHide;
import com.grabx.app.grabx.ui.sidebar.SidebarItem;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;

import java.util.prefs.Preferences;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

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
    private StackPane searchBox;
    @FXML
    private Button searchToggleButton;
    @FXML
    private ComboBox<String> historyFilter;

    @FXML
    private Label statusText;
    @FXML
    private Label globalSpeed;
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


    private PlaylistFlowService playlistFlowService;

    private AddLinkFlowService addLinkFlowService;

    private HoverTooltipService hoverTooltipService;

    private final AtomicLong downloadOrderSeq = new AtomicLong(0);

    private SidebarService sidebarService;
    private DownloadMonitoringService downloadMonitoringService;
    private GlobalSpeedService globalSpeedService;
    private Timeline searchAnimation;
    private boolean searchExpanded;
    private SVGPath searchToggleIcon;
    private static final String SEARCH_ICON_PATH =
            "M4,9.5 A5.5,5.5 0 1,0 15,9.5 A5.5,5.5 0 1,0 4,9.5 M13.5,13.5 L20,20";
    private static final String CLOSE_SEARCH_ICON_PATH = "M6,6 L18,18 M18,6 L6,18";


    private final Map<DownloadRow, Process> activeProcesses = new ConcurrentHashMap<>();

    private DownloadStateCoordinator downloadStateCoordinator;
    private DownloadRunner downloadRunner;
    private final Map<DownloadRow, String> stopReasons = new ConcurrentHashMap<>();
    // Persist last selected download folder
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainController.class);

    private final ObservableList<DownloadRow> downloadItems = FXCollections.observableArrayList();

    private final DownloadService downloadService = new DownloadService(downloadItems);
    private final DownloadTitleService downloadTitleService =
            new DownloadTitleService(
                    downloadItems,
                    Platform::runLater,
                    text -> { if (statusText != null) statusText.setText(text); }
            );
    private final DownloadHistoryReconciler downloadHistoryReconciler =
            new DownloadHistoryReconciler(
                    downloadItems,
                    Platform::runLater,
                    downloadService::refilter,
                    this::updateMissingSidebarItem
            );

    private final HistoryService historyService =
            new HistoryService(
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


    public static final String QUALITY_BEST = "Best quality (Recommended)";
    public static final String QUALITY_SEPARATOR = "──────────────";
    private static final String MODE_VIDEO = "Video";
    public static final String MODE_AUDIO = "Audio only";

    public static final String AUDIO_BEST = "Best audio (Recommended)";
    public static final String AUDIO_DEFAULT_FORMAT = "mp3";
    private static final List<String> AUDIO_FORMATS = List.of(
            "m4a", "mp3", "opus", "aac", "wav", "flac"
    );

    @FXML
    public void initialize() {
        IconButtonService iconButtons = initializeWindowUi();
        initializeSidebar();
        initializeGlobalSpeed();
        initializeDownloadServices();
        initializeDownloadsList(iconButtons);

        historyService.loadOnce();
        initializePlaylistServices();
        initializeAddLink();
        initializeMonitoring();
    }

    private IconButtonService initializeWindowUi() {
        Platform.runLater(() -> ScrollbarAutoHide.enableGlobalAutoHide(root));
        Platform.runLater(() -> {
            if (root != null) root.requestFocus();
        });
        AddLinkDialogFactory.installClickToDefocus(root);
        initializeHistoryFilter();
        initializeExpandableSearch();

        try {
            if (hoverTooltipService == null && root != null) {
                hoverTooltipService = new HoverTooltipService(root, MainController.class);
            }
        } catch (Exception ignored) {}

        IconButtonService iconButtons = new IconButtonService(hoverTooltipService);
        iconButtons.initializeToolbar(
                addLinkButton, pauseAllButton, resumeAllButton,
                cancelAllBtn, clearAllButton, settingsButton
        );
        return iconButtons;
    }

    private void initializeHistoryFilter() {
        if (historyFilter == null) return;
        historyFilter.getItems().setAll("Newest", "Oldest", "Last 5", "Last 10");
        historyFilter.getSelectionModel().select("Newest");
        historyFilter.valueProperty().addListener((observable, oldValue, newValue) ->
                downloadService.setHistoryView(newValue));
        downloadService.setHistoryView("Newest");
    }

    private void initializeExpandableSearch() {
        if (searchBox == null || searchField == null || searchToggleButton == null) return;

        searchToggleIcon = new SVGPath();
        searchToggleIcon.setContent(SEARCH_ICON_PATH);
        searchToggleIcon.getStyleClass().add("gx-search-icon");
        searchToggleIcon.setScaleX(1.15);
        searchToggleIcon.setScaleY(1.15);
        searchToggleButton.setGraphic(searchToggleIcon);
        searchToggleButton.setFocusTraversable(false);

        searchField.setVisible(false);
        searchField.setOpacity(0);
        searchField.setMouseTransparent(true);
        searchToggleButton.setOnAction(event -> setSearchExpanded(!searchExpanded));
        searchField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                setSearchExpanded(false);
                event.consume();
            }
        });
    }

    private void setSearchExpanded(boolean expanded) {
        if (searchBox == null || searchField == null) return;
        searchExpanded = expanded;
        if (searchAnimation != null) searchAnimation.stop();
        if (searchToggleIcon != null) {
            searchToggleIcon.setContent(expanded ? CLOSE_SEARCH_ICON_PATH : SEARCH_ICON_PATH);
            searchToggleIcon.setScaleX(expanded ? 1.0 : 1.15);
            searchToggleIcon.setScaleY(expanded ? 1.0 : 1.15);
        }
        searchToggleButton.getStyleClass().remove("gx-search-toggle-expanded");
        if (expanded) searchToggleButton.getStyleClass().add("gx-search-toggle-expanded");

        if (expanded) {
            searchField.setVisible(true);
            searchField.setMouseTransparent(false);
        } else {
            searchField.clear();
            searchField.setMouseTransparent(true);
        }

        double currentWidth = searchBox.getWidth() > 0 ? searchBox.getWidth() : searchBox.getPrefWidth();
        searchBox.setPrefWidth(currentWidth);
        searchAnimation = new Timeline(new KeyFrame(
                Duration.millis(220),
                new KeyValue(searchBox.prefWidthProperty(), expanded ? 260.0 : 46.0,
                        javafx.animation.Interpolator.EASE_BOTH),
                new KeyValue(searchField.opacityProperty(), expanded ? 1.0 : 0.0,
                        javafx.animation.Interpolator.EASE_BOTH)
        ));
        searchAnimation.setOnFinished(event -> {
            if (expanded) {
                searchField.requestFocus();
                searchField.positionCaret(searchField.getText() == null ? 0 : searchField.getText().length());
            } else {
                searchField.setVisible(false);
            }
        });
        searchAnimation.play();
    }

    private void initializeSidebar() {
        sidebarService = new SidebarService(
                sidebarList, contentTitle, statusText, searchField, downloadItems, downloadService
        );
        sidebarService.initialize();
    }

    private void initializeGlobalSpeed() {
        globalSpeedService = new GlobalSpeedService(
                downloadItems,
                text -> { if (globalSpeed != null) globalSpeed.setText(text); },
                text -> { if (statusText != null) statusText.setText(text); }
        );
        globalSpeedService.start();
    }

    private void initializeDownloadServices() {
        DownloadServicesFactory.Runtime downloadRuntime = DownloadServicesFactory.create(
                downloadItems,
                activeProcesses,
                stopReasons,
                downloadOrderSeq,
                new DownloadServicesFactory.Dependencies(
                        downloadFolderPreferences::getLastFolderOrDefault,
                        historyService::attachAutoSave,
                        historyService::scheduleSave,
                        thumbnailService::applyToRow,
                        downloadTitleService::resolveAsync,
                        Platform::runLater,
                        text -> { if (statusText != null) statusText.setText(text); },
                        this::updateMissingSidebarItem,
                        sidebarService::refilter,
                        historyService::clearHistoryFile
                ),
                new DownloadServicesFactory.Config(
                        MODE_VIDEO, MODE_AUDIO, QUALITY_BEST, QUALITY_SEPARATOR,
                        AUDIO_BEST, AUDIO_DEFAULT_FORMAT
                )
        );
        downloadStateCoordinator = downloadRuntime.stateCoordinator();
        downloadRunner = downloadRuntime.runner();
        downloadQueueService = downloadRuntime.queue();
        bulkDownloadActionsService = downloadRuntime.bulkActions();
    }

    private void initializeDownloadsList(IconButtonService iconButtons) {
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
    }

    private void initializeAddLink() {
        AddLinkServicesFactory.Runtime addLinkRuntime = AddLinkServicesFactory.create(
                root,
                UI_DELAY_EXEC,
                new AddLinkServicesFactory.Dependencies(
                        downloadFolderPreferences::getLastFolderOrDefault,
                        downloadFolderPreferences::saveLastFolder,
                        downloadQueueService::enqueue,
                        (playlistUrl, folder) -> {
                            if (playlistFlowService != null) {
                                playlistFlowService.open(ownerWindow(), playlistUrl, folder);
                            }
                        },
                        text -> { if (statusText != null) statusText.setText(text); },
                        urlAnalysisService::isHttpUrl,
                        ClipboardService::readClipboardTextSafe,
                        Platform::runLater
                ),
                AddLinkDialogFactory.defaultConfig(
                        MODE_VIDEO, MODE_AUDIO, QUALITY_BEST, QUALITY_SEPARATOR,
                        AUDIO_DEFAULT_FORMAT, AUDIO_FORMATS
                )
        );
        addLinkFlowService = addLinkRuntime.flow();

        if (addLinkButton != null) {
            addLinkButton.setOnAction(ev -> {
                if (addLinkFlowService != null) addLinkFlowService.showFromClipboardDeferred();
            });
        }
    }

    private void initializeMonitoring() {
        downloadMonitoringService = new DownloadMonitoringService(
                downloadItems,
                UI_DELAY_EXEC,
                root,
                historyService::attachAutoSave,
                historyService::scheduleSave,
                this::updateMissingSidebarItem,
                sidebarService::refilter,
                addLinkFlowService == null ? url -> {} : addLinkFlowService::openOrUpdate,
                urlAnalysisService::isHttpUrl
        );
        downloadMonitoringService.start();
    }

    private void initializePlaylistServices() {
        PlaylistServicesFactory.Runtime playlistRuntime = PlaylistServicesFactory.create(
                playlistDialogService::show,
                new PlaylistServicesFactory.Dependencies(
                        downloadQueueService::create,
                        downloadItems::add,
                        thumbnailService::applyToRow,
                        historyService::attachAutoSave,
                        historyService::scheduleSave,
                        row -> startDownloadRow(row, false),
                        sidebarService::refilter,
                        Platform::runLater,
                        com.grabx.app.grabx.util.YouTubeUrls::watchUrl,
                        com.grabx.app.grabx.util.YouTubeUrls::extractVideoId,
                        text -> { if (statusText != null && text != null) statusText.setText(text); },
                        downloadFolderPreferences::getLastFolderOrDefault,
                        downloadFolderPreferences::saveLastFolder,
                        () -> { if (addLinkFlowService != null) addLinkFlowService.completePlaylist(); },
                        () -> { if (addLinkFlowService != null) addLinkFlowService.returnFromPlaylist(); }
                )
        );
        playlistFlowService = playlistRuntime.flow();
    }

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
    private static final ScheduledExecutorService UI_DELAY_EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-delay");
        t.setDaemon(true);
        return t;
    });

    private void updateMissingSidebarItem() {
        if (sidebarService != null) sidebarService.refreshMissingItem();
    }

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


    private javafx.stage.Window ownerWindow() {
        try {
            if (root != null && root.getScene() != null) {
                return root.getScene().getWindow();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
