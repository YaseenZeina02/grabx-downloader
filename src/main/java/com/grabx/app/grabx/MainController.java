package com.grabx.app.grabx;
import com.grabx.app.grabx.ui.components.DownloadRowActions;
import com.grabx.app.grabx.ui.components.CompactDownloadRowCell;
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
import com.grabx.app.grabx.browser.BrowserBridgeService;
import com.grabx.app.grabx.browser.BrowserCapture;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

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
import javafx.scene.Node;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

public class MainController {
    private com.grabx.app.grabx.ui.components.SettingsPage settingsPage;
    private com.grabx.app.grabx.core.service.DownloadScheduler downloadScheduler;
    private final com.grabx.app.grabx.core.service.TaskPreferences taskPreferences =
            new com.grabx.app.grabx.core.service.TaskPreferences(Preferences.userNodeForPackage(MainController.class));
    private Node homeContent;
    private Node settingsGraphic;
    private final com.grabx.app.grabx.core.service.TransferPreferences transferPreferences =
            new com.grabx.app.grabx.core.service.TransferPreferences(Preferences.userNodeForPackage(MainController.class));
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
    private Button compactViewButton;

    @FXML
    private Label contentTitle;
    @FXML
    private ListView<SidebarItem> sidebarList;

    @FXML
    private ListView<DownloadRow> downloadsList;


    private PlaylistFlowService playlistFlowService;

    private AddLinkFlowService addLinkFlowService;
    private com.grabx.app.grabx.core.service.PartialRetentionService partialRetention;
    private int partialRetentionDays() { return Math.max(1, Math.min(365, PREFS.getInt("partialRetentionDays", 2))); }

    private HoverTooltipService hoverTooltipService;

    private final AtomicLong downloadOrderSeq = new AtomicLong(0);

    private SidebarService sidebarService;
    private DownloadMonitoringService downloadMonitoringService;
    private GlobalSpeedService globalSpeedService;
    private BrowserBridgeService browserBridgeService;
    private com.grabx.app.grabx.core.service.SoundFeedbackService soundFeedback;
    private com.grabx.app.grabx.core.service.DownloadSoundObserver downloadSounds;
    private Timeline searchAnimation;
    private boolean searchExpanded;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private boolean compactView;
    private VBox compactRoot;
    private javafx.stage.Stage compactStage;
    private javafx.stage.Stage fullStage;
    private double compactDragX;
    private double compactDragY;
    private static final double COMPACT_MIN_WIDTH = 350;
    private static final double COMPACT_MIN_HEIGHT = 160;
    private static final double COMPACT_MAX_WIDTH = 390;
    private static final double COMPACT_MAX_HEIGHT = 290;
    private boolean compactDragging;
    private boolean compactResizing;
    private double compactResizeScreenX;
    private double compactResizeScreenY;
    private double compactResizeWidth;
    private double compactResizeHeight;
    private double compactResizeStageX;
    private double compactResizeStageY;
    private boolean compactResizeLeft;
    private boolean compactResizeRight;
    private boolean compactResizeTop;
    private boolean compactResizeBottom;
    private com.grabx.app.grabx.ui.components.CompactWindowState fullWindowState;
    private boolean compactTransitioning;
    private SVGPath searchToggleIcon;
    private static final String SEARCH_ICON_PATH =
            "M4,9.5 A5.5,5.5 0 1,0 15,9.5 A5.5,5.5 0 1,0 4,9.5 M13.5,13.5 L20,20";
    private static final String CLOSE_SEARCH_ICON_PATH = "M6,6 L18,18 M18,6 L6,18";
    private static final double SEARCH_COLLAPSED_WIDTH = 38.0;


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
        initializeCompactView(iconButtons);

        soundFeedback = new com.grabx.app.grabx.core.service.SoundFeedbackService(PREFS);
        downloadSounds = new com.grabx.app.grabx.core.service.DownloadSoundObserver(downloadItems, soundFeedback::play);
        historyService.loadOnce();
        initializePlaylistServices();
        initializeAddLink();
        initializeBrowserBridge();
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
                cancelAllBtn, clearAllButton, settingsButton, compactViewButton
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
        searchToggleIcon.setScaleX(0.92);
        searchToggleIcon.setScaleY(0.92);
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
            searchToggleIcon.setScaleX(expanded ? 0.82 : 0.92);
            searchToggleIcon.setScaleY(expanded ? 0.82 : 0.92);
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
                new KeyValue(searchBox.prefWidthProperty(), expanded ? 260.0 : SEARCH_COLLAPSED_WIDTH,
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
        downloadScheduler = downloadRuntime.scheduler();
        downloadScheduler.setLimit(taskPreferences.maxActive());
        downloadRunner.configureTransferLimits(transferPreferences.directRate(), transferPreferences.mediaRate());
        downloadQueueService = downloadRuntime.queue();
        bulkDownloadActionsService = downloadRuntime.bulkActions();
    }

    private void initializeDownloadsList(IconButtonService iconButtons) {
        javafx.scene.control.MenuItem refreshLink = new javafx.scene.control.MenuItem("Update download link and resume…");
        refreshLink.setOnAction(event -> {
            DownloadRow row = downloadsList.getSelectionModel().getSelectedItem();
            if (!canRefreshLink(row)) return;
            javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
            dialog.initOwner(ownerWindow());
            dialog.setTitle("Update download link");
            dialog.setHeaderText("Paste a fresh direct link for " + row.title.get());
            dialog.setContentText("Saved data will be checked before resuming:");
            dialog.showAndWait().filter(value -> !value.isBlank())
                    .ifPresent(value -> refreshDownloadLink(row, value.trim(), row.getReferer()));
        });
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(refreshLink);
        menu.setOnShowing(event -> refreshLink.setDisable(!canRefreshLink(downloadsList.getSelectionModel().getSelectedItem())));
        downloadsList.setContextMenu(menu);
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
        initializeMainEmptyState();
    }

    private void initializeMainEmptyState() {
        StackPane emptyIcon = new StackPane(IconButtonService.createSvgIcon(
                IconButtonService.DOWNLOAD_TRAY, 42));
        emptyIcon.getStyleClass().add("gx-main-empty-icon");
        Label emptyTitle = new Label();
        emptyTitle.getStyleClass().add("gx-main-empty-title");
        Label emptyHint = new Label();
        emptyHint.getStyleClass().add("gx-main-empty-hint");
        VBox emptyState = new VBox(8, emptyIcon, emptyTitle, emptyHint);
        emptyState.setAlignment(javafx.geometry.Pos.CENTER);
        emptyState.getStyleClass().add("gx-main-empty");
        downloadsList.setPlaceholder(emptyState);

        Runnable updateCopy = () -> {
            boolean libraryEmpty = downloadItems.isEmpty();
            boolean searching = searchField != null && !searchField.getText().isBlank();
            String section = contentTitle == null ? "" : contentTitle.getText();
            if (libraryEmpty) {
                emptyTitle.setText("No downloads yet");
                emptyHint.setText("Add a link to start your first download");
            } else if (searching) {
                emptyTitle.setText("No matching downloads");
                emptyHint.setText("Try a different search term");
            } else {
                switch (section == null ? "" : section) {
                    case "Downloading" -> {
                        emptyTitle.setText("No active downloads");
                        emptyHint.setText("New downloads will appear here");
                    }
                    case "Paused" -> {
                        emptyTitle.setText("No paused downloads");
                        emptyHint.setText("Paused downloads will appear here");
                    }
                    case "Completed" -> {
                        emptyTitle.setText("No completed downloads");
                        emptyHint.setText("Finished downloads will appear here");
                    }
                    case "Cancelled" -> {
                        emptyTitle.setText("No cancelled downloads");
                        emptyHint.setText("Cancelled downloads will appear here");
                    }
                    default -> {
                        emptyTitle.setText("No matching downloads");
                        emptyHint.setText("Try another library filter");
                    }
                }
            }
        };
        updateCopy.run();
        downloadItems.addListener((javafx.beans.InvalidationListener) observable -> updateCopy.run());
        if (searchField != null) searchField.textProperty().addListener(observable -> updateCopy.run());
        if (contentTitle != null) contentTitle.textProperty().addListener(observable -> updateCopy.run());
    }

    private void initializeCompactView(IconButtonService iconButtons) {
        ListView<DownloadRow> compactList = new ListView<>(downloadService.activeView());
        compactList.getStyleClass().add("gx-compact-list");
        compactList.setSelectionModel(new com.grabx.app.grabx.ui.components.NoSelectionModel<>());
        StackPane emptyIcon = new StackPane(IconButtonService.createSvgIcon(
                IconButtonService.DOWNLOAD_TRAY, 34));
        emptyIcon.getStyleClass().add("gx-compact-empty-icon");
        Label emptyText = new Label("No active downloads");
        emptyText.getStyleClass().add("gx-compact-empty-text");
        VBox emptyState = new VBox(9, emptyIcon, emptyText);
        emptyState.setAlignment(javafx.geometry.Pos.CENTER);
        emptyState.getStyleClass().add("gx-compact-empty");
        compactList.setPlaceholder(emptyState);

        Button restoreButton = new Button();
        IconButtonService.setupSvgButton(restoreButton, IconButtonService.FULL_VIEW);
        restoreButton.getStyleClass().add("gx-compact-restore");
        iconButtons.installTooltip(restoreButton, "Full View");
        restoreButton.setOnAction(event -> exitCompactView());

        Button closeButton = new Button();
        Button minimizeButton = new Button();
        closeButton.setGraphic(IconButtonService.createSvgIcon(IconButtonService.CANCEL, 22));
        minimizeButton.setGraphic(IconButtonService.createSvgIcon("M5 11h14v2H5z", 22));
        closeButton.setAccessibleText("Close");
        minimizeButton.setAccessibleText("Minimize");
        closeButton.setFocusTraversable(false);
        minimizeButton.setFocusTraversable(false);
        closeButton.getStyleClass().addAll("gx-window-control", "gx-window-close");
        minimizeButton.getStyleClass().addAll("gx-window-control", "gx-window-minimize");
        closeButton.setOnAction(event -> {
            shutdown();
            Platform.exit();
        });
        minimizeButton.setOnAction(event -> {
            if (compactStage != null) compactStage.setIconified(true);
        });
        HBox windowControls = new HBox(7, closeButton, minimizeButton);
        windowControls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        windowControls.getStyleClass().add("gx-window-controls");

        BorderPane compactToolbar = new BorderPane();
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        compactToolbar.setLeft(mac ? windowControls : restoreButton);
        compactToolbar.setRight(mac ? restoreButton : windowControls);
        compactToolbar.getStyleClass().add("gx-compact-toolbar");
        compactToolbar.setPickOnBounds(true);
        // Share the toolbar animation so media fades down and returns in exact sync,
        // including cells created/reused while the overlay is already visible.
        javafx.beans.binding.DoubleBinding mediaOpacity =
                compactToolbar.opacityProperty().multiply(-0.78).add(1);
        emptyIcon.opacityProperty().bind(mediaOpacity);
        compactList.setCellFactory(list -> new CompactDownloadRowCell(
                downloadStateCoordinator::pause,
                downloadStateCoordinator::resume,
                downloadStateCoordinator::cancel,
                iconButtons::installTooltip,
                (node, text) -> hoverTooltipService.install(node, text),
                mediaOpacity,
                this::showCompactVerificationDetails
        ));

        Label activeCount = new Label();
        activeCount.textProperty().bind(javafx.beans.binding.Bindings.size(downloadService.activeView())
                .asString("%d active"));
        activeCount.getStyleClass().add("gx-compact-footer-text");
        Label compactSpeed = new Label();
        if (globalSpeed != null) compactSpeed.textProperty().bind(globalSpeed.textProperty());
        compactSpeed.getStyleClass().add("gx-compact-footer-text");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox compactFooter = new HBox(8, activeCount, footerSpacer, compactSpeed);
        compactFooter.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        compactFooter.getStyleClass().add("gx-compact-footer");

        // Overlay window controls without reserving a title-bar row. Keeping the
        // toolbar inside the hover target prevents flicker when entering a button.
        // The transparent header region stays pickable while its controls fade out.
        // Hovering the download body or footer must not reveal the toolbar.
        StackPane headerHoverArea = new StackPane(compactToolbar);
        headerHoverArea.setMaxHeight(Region.USE_PREF_SIZE);
        headerHoverArea.setPickOnBounds(true);
        StackPane downloadArea = new StackPane(compactList, headerHoverArea);
        downloadArea.setMinHeight(0);
        compactList.setMinHeight(0);
        StackPane.setAlignment(headerHoverArea, javafx.geometry.Pos.TOP_CENTER);
        compactRoot = new VBox(4, downloadArea, compactFooter);
        compactRoot.getStyleClass().add("gx-compact-root");
        compactToolbar.setOpacity(0);
        compactToolbar.setVisible(false);
        compactToolbar.setMouseTransparent(true);
        javafx.animation.FadeTransition toolbarFade = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(140), compactToolbar);
        headerHoverArea.hoverProperty().addListener((observable, wasHovered, hovered) -> {
            toolbarFade.stop();
            compactToolbar.setMouseTransparent(!hovered);
            if (hovered) compactToolbar.setVisible(true);
            toolbarFade.setFromValue(compactToolbar.getOpacity());
            toolbarFade.setToValue(hovered ? 1 : 0);
            toolbarFade.setOnFinished(event -> {
                if (!headerHoverArea.isHover()) compactToolbar.setVisible(false);
            });
            toolbarFade.playFromStart();
        });
        compactRoot.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, event -> {
            if (compactStage == null) return;
            boolean nearLeft = event.getSceneX() <= 10;
            boolean nearRight = event.getSceneX() >= compactStage.getWidth() - 10;
            boolean nearTop = event.getSceneY() <= 10;
            boolean nearBottom = event.getSceneY() >= compactStage.getHeight() - 10;
            if (nearLeft && nearTop) compactRoot.setCursor(javafx.scene.Cursor.NW_RESIZE);
            else if (nearRight && nearTop) compactRoot.setCursor(javafx.scene.Cursor.NE_RESIZE);
            else if (nearLeft && nearBottom) compactRoot.setCursor(javafx.scene.Cursor.SW_RESIZE);
            else if (nearRight && nearBottom) compactRoot.setCursor(javafx.scene.Cursor.SE_RESIZE);
            else if (nearLeft) compactRoot.setCursor(javafx.scene.Cursor.W_RESIZE);
            else if (nearRight) compactRoot.setCursor(javafx.scene.Cursor.E_RESIZE);
            else if (nearTop) compactRoot.setCursor(javafx.scene.Cursor.N_RESIZE);
            else if (nearBottom) compactRoot.setCursor(javafx.scene.Cursor.S_RESIZE);
            else compactRoot.setCursor(isCompactInteractiveTarget(event.getTarget())
                    ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.OPEN_HAND);
        });
        compactRoot.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            compactDragging = false;
            compactResizeLeft = event.getSceneX() <= 10;
            compactResizeRight = compactStage != null && event.getSceneX() >= compactStage.getWidth() - 10;
            compactResizeTop = event.getSceneY() <= 10;
            compactResizeBottom = compactStage != null && event.getSceneY() >= compactStage.getHeight() - 10;
            boolean resizeEdge = compactStage != null && (compactResizeLeft || compactResizeRight
                    || compactResizeTop || compactResizeBottom);
            if (resizeEdge) {
                compactResizing = true;
                compactResizeScreenX = event.getScreenX();
                compactResizeScreenY = event.getScreenY();
                compactResizeWidth = compactStage.getWidth();
                compactResizeHeight = compactStage.getHeight();
                compactResizeStageX = compactStage.getX();
                compactResizeStageY = compactStage.getY();
                event.consume();
                return;
            }
            if (compactStage == null || isCompactInteractiveTarget(event.getTarget())) return;
            compactDragging = true;
            // A fixed screen-space offset avoids feedback from moving scene coordinates.
            compactDragX = event.getScreenX() - compactStage.getX();
            compactDragY = event.getScreenY() - compactStage.getY();
            compactRoot.setCursor(javafx.scene.Cursor.CLOSED_HAND);
            event.consume();
        });
        compactRoot.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, event -> {
            if (compactResizing && compactStage != null) {
                double dx = event.getScreenX() - compactResizeScreenX;
                double dy = event.getScreenY() - compactResizeScreenY;
                if (compactResizeLeft || compactResizeRight) {
                    double requestedWidth = compactResizeWidth + (compactResizeLeft ? -dx : dx);
                    double newWidth = Math.max(COMPACT_MIN_WIDTH, Math.min(COMPACT_MAX_WIDTH, requestedWidth));
                    if (compactResizeLeft) {
                        compactStage.setX(compactResizeStageX + compactResizeWidth - newWidth);
                    }
                    compactStage.setWidth(newWidth);
                }
                if (compactResizeTop || compactResizeBottom) {
                    double requestedHeight = compactResizeHeight + (compactResizeTop ? -dy : dy);
                    double newHeight = Math.max(COMPACT_MIN_HEIGHT, Math.min(COMPACT_MAX_HEIGHT, requestedHeight));
                    if (compactResizeTop) {
                        compactStage.setY(compactResizeStageY + compactResizeHeight - newHeight);
                    }
                    compactStage.setHeight(newHeight);
                }
                event.consume();
                return;
            }
            if (!compactDragging || compactStage == null) return;
            compactStage.setX(event.getScreenX() - compactDragX);
            compactStage.setY(event.getScreenY() - compactDragY);
            event.consume();
        });
        compactRoot.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (compactDragging || compactResizing) event.consume();
            compactRoot.setCursor(isCompactInteractiveTarget(event.getTarget())
                    ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.OPEN_HAND);
            compactResizing = false;
            compactDragging = false;
            compactResizeLeft = compactResizeRight = compactResizeTop = compactResizeBottom = false;
        });
        VBox.setVgrow(downloadArea, Priority.ALWAYS);
    }

    private void showCompactVerificationDetails() {
        // A full verification card needs more room than a normal compact row.
        // Grow only when needed; do not shrink/reposition a user's window afterward.
        if (compactView && compactStage != null && compactStage.isShowing() && compactStage.getHeight() < 260) {
            compactStage.setHeight(260);
        }
    }

    private static boolean isCompactInteractiveTarget(Object target) {
        // SVG paths and labels inside a button can be the actual event target.
        for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
            if (node instanceof ButtonBase || node instanceof ScrollBar) return true;
        }
        return false;
    }

    private void enterCompactView() {
        if (compactView || compactTransitioning || root == null
                || compactRoot == null || root.getScene() == null) return;
        javafx.stage.Window window = root.getScene().getWindow();
        if (!(window instanceof javafx.stage.Stage stage)) return;
        fullStage = stage;

        fullWindowState = com.grabx.app.grabx.ui.components.CompactWindowState.capture(stage);
        compactView = true;

        double compactWidth = COMPACT_MIN_WIDTH;
        boolean checkingSavedData = downloadItems.stream().anyMatch(row ->
                row.getState() == DownloadRow.State.DOWNLOADING && row.status.get() != null
                        && row.status.get().startsWith("Verifying saved data"));
        double compactHeight = checkingSavedData ? 260 : COMPACT_MIN_HEIGHT;
        if (compactStage == null) {
            compactStage = new javafx.stage.Stage(javafx.stage.StageStyle.TRANSPARENT);
            javafx.scene.Scene compactScene = new javafx.scene.Scene(compactRoot, compactWidth, compactHeight);
            compactScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            // The theme is declared on the FXML root rather than the original
            // Scene, so copy both sources when Compact View gets its own Scene.
            compactScene.getStylesheets().setAll(root.getScene().getStylesheets());
            compactScene.getStylesheets().addAll(root.getStylesheets());
            compactStage.setScene(compactScene);
            compactStage.setMinWidth(COMPACT_MIN_WIDTH);
            compactStage.setMaxWidth(COMPACT_MAX_WIDTH);
            compactStage.setMinHeight(COMPACT_MIN_HEIGHT);
            compactStage.setMaxHeight(COMPACT_MAX_HEIGHT);
            compactStage.setAlwaysOnTop(true);
        }
        compactStage.setWidth(compactWidth);
        compactStage.setHeight(compactHeight);
        compactStage.setX(fullWindowState.x() + Math.max(0, (fullWindowState.width() - compactWidth) / 2));
        compactStage.setY(fullWindowState.y() + Math.max(0, (fullWindowState.height() - compactHeight) / 2));
        stage.hide();
        compactStage.setIconified(false);
        compactStage.show();
        if (compactStage.getProperties().putIfAbsent("grabx-smart-scroll", Boolean.TRUE) == null) {
            Platform.runLater(() -> ScrollbarAutoHide.enableGlobalAutoHide(compactRoot));
        }
        compactStage.toFront();
    }

    private void exitCompactView() {
        if (!compactView || compactTransitioning || fullStage == null || fullWindowState == null) return;
        compactTransitioning = true;
        if (compactStage != null) compactStage.hide();
        fullWindowState.restore(fullStage, () -> {
            compactView = false;
            compactTransitioning = false;
        });
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
                addLinkFlowService == null ? url -> {} : addLinkFlowService::openFromClipboardMonitor,
                urlAnalysisService::isHttpUrl
        );
        downloadMonitoringService.start();
        partialRetention = new com.grabx.app.grabx.core.service.PartialRetentionService(
                downloadItems, activeProcesses, downloadStateCoordinator::cancel,
                this::partialRetentionDays, historyService::scheduleSave);
        partialRetention.start();
    }

    private void initializeBrowserBridge() {
        browserBridgeService = new BrowserBridgeService(capture ->
                Platform.runLater(() -> handleBrowserCapture(capture)));
        browserBridgeService.start();
    }

    private void handleBrowserCapture(BrowserCapture capture) {
        if (capture == null) return;
        try {
            if (root != null && root.getScene() != null
                    && root.getScene().getWindow() instanceof javafx.stage.Stage stage) {
                if (compactView) exitCompactView();
                com.grabx.app.grabx.core.service.WindowActivationService.fromBrowser(stage);
            }
            if (statusText != null) statusText.setText("Received from browser: " + capture.title());
            if ("file".equalsIgnoreCase(capture.action()) && downloadQueueService != null) {
                String folder = capture.suggestedFolder();
                if (folder == null || folder.isBlank()) {
                    folder = downloadFolderPreferences.getLastFolderOrDefault();
                }
                String filename = capture.suggestedFilename();
                if (filename == null || filename.isBlank()) filename = capture.title();
                if (addLinkFlowService != null) addLinkFlowService.browserDownloadStarted();
                java.nio.file.Path browserFolder = capture.browserDestinationDirectory();
                if (browserFolder != null) {
                    folder = browserFolder.toString();
                } else {
                    javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                    chooser.setTitle("Choose download folder — " + filename);
                    java.io.File initial = new java.io.File(folder);
                    if (initial.isDirectory()) chooser.setInitialDirectory(initial);
                    java.io.File selected = chooser.showDialog(ownerWindow());
                    if (selected == null) {
                        if (statusText != null) statusText.setText("Browser download cancelled");
                        return;
                    }
                    folder = selected.getAbsolutePath();
                }
                downloadFolderPreferences.saveLastFolder(folder);
                downloadQueueService.enqueueDirect(capture.effectiveUrl(), folder, filename, capture.pageUrl(), capture.userAgent());
                if (addLinkFlowService != null) addLinkFlowService.browserDownloadStarted();
                if (statusText != null) statusText.setText("Browser download started in GrabX");
            } else if (addLinkFlowService != null) {
                addLinkFlowService.offerBrowserQualities(capture.effectiveUrl(), capture.availableQualities(), capture.qualitySizes());
                addLinkFlowService.openOrUpdate(
                        capture.effectiveUrl(), capture.action(), true, capture.suggestedFolder());
            }
        } catch (Exception exception) {
            if (statusText != null) statusText.setText("Could not start browser download: " + exception.getMessage());
        }
    }

    private final java.util.Map<DownloadRow, Object> linkChecks = new java.util.HashMap<>();

    private boolean canRefreshLink(DownloadRow row) {
        if (row == null || !"Direct".equalsIgnoreCase(row.mode)) return false;
        var state = row.getState();
        if (state != DownloadRow.State.PAUSED && state != DownloadRow.State.FAILED) return false;
        try { return com.grabx.app.grabx.core.service.DirectPartialFiles.hasParts(row.outputFile.get()); }
        catch (Exception error) { return false; }
    }

    private void refreshDownloadLink(DownloadRow row, String url, String referer) {
        if (!canRefreshLink(row)) return;
        Process process = activeProcesses.get(row);
        if (process != null && process.isAlive()) {
            stopReasons.put(row, "PAUSE");
            process.destroyForcibly();
            Thread waiting = new Thread(() -> {
                try {
                    process.waitFor();
                    Platform.runLater(() -> {
                        if (downloadItems.contains(row) && canRefreshLink(row))
                            refreshDownloadLink(row, url, referer);
                    });
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, "download-link-wait");
            waiting.setDaemon(true); waiting.start();
            return;
        }
        Object check = new Object();
        linkChecks.put(row, check);
        java.nio.file.Path output = row.outputFile.get();
        long total = row.totalBytes.get();
        row.setState(DownloadRow.State.PENDING);
        row.status.set("Checking saved data against the new link…");
        Thread verification = new Thread(() -> {
            try {
                var verified = com.grabx.app.grabx.core.service.DownloadLinkVerifier.verify(url, referer, output, total, row.resumeSegments > 0 ? row.resumeSegments
                        : com.grabx.app.grabx.core.service.DownloadRunner.directSegmentCountForLegacy(total), row.requestUserAgent);
                Platform.runLater(() -> {
                    if (linkChecks.get(row) != check || !downloadItems.contains(row) || row.getState() != DownloadRow.State.PENDING) return;
                    linkChecks.remove(row);
                    row.url = url;
                    row.resumeEtag = verified.etag();
                    row.totalBytes.set(verified.total());
                    row.setReferer(referer);
                    row.resumeTotal = verified.total();
                    row.resumeSegments = verified.segments();
                    row.resumeLastModified = null;
                    row.finalUrl = null;
                    row.resumeSupport = "Yes";
                    try { com.grabx.app.grabx.core.service.DirectDownloadState.save(row, output); }
                    catch (java.io.IOException e) {
                        row.setState(DownloadRow.State.FAILED);
                        row.status.set("Could not save verified link; saved data kept");
                        historyService.scheduleSave();
                        return;
                    }
                    historyService.saveNow();
                    startDownloadRow(row, true);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (linkChecks.get(row) != check || !downloadItems.contains(row) || row.getState() != DownloadRow.State.PENDING) return;
                    linkChecks.remove(row);
                    row.setState(DownloadRow.State.FAILED);
                    row.status.set("Link not updated: " + error.getMessage());
                    historyService.scheduleSave();
                });
            }
        }, "download-link-verification");
        verification.setDaemon(true);
        verification.start();
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
        if (settingsPage != null) { leaveSettings(); return; }
        homeContent = root.getCenter();
        settingsGraphic = settingsButton.getGraphic();
        settingsPage = new com.grabx.app.grabx.ui.components.SettingsPage(
                new com.grabx.app.grabx.ui.components.SettingsPage.Values(
                        downloadFolderPreferences.configuredFolder(), downloadFolderPreferences.remembersLastFolder(),
                        transferPreferences.directRate(), transferPreferences.mediaRate(),
                        soundFeedback.isEnabled(), soundFeedback.volume(), partialRetentionDays(), taskPreferences.maxActive()),
                soundFeedback, settings -> {
                    downloadFolderPreferences.configure(settings.folder(), settings.rememberFolder());
                    transferPreferences.save(settings.directRate(), settings.mediaRate());
                    taskPreferences.save(settings.maxActive());
                    downloadScheduler.setLimit(settings.maxActive());
                    PREFS.putInt("partialRetentionDays", settings.retentionDays());
                    soundFeedback.configure(settings.sounds(), settings.volume());
                    downloadRunner.configureTransferLimits(settings.directRate(), settings.mediaRate());
                });
        root.setCenter(settingsPage);
        settingsButton.setGraphic(IconButtonService.createSvgIcon("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.42-1.41L7.83 13H20v-2z", 34));
        settingsButton.setAccessibleText("Back to downloads");
        if (hoverTooltipService != null) hoverTooltipService.install(settingsButton, "Back to downloads");
        showHomeToolbar(false);
    }

    private void showHomeToolbar(boolean show) {
        for (Button button : List.of(addLinkButton, pauseAllButton, resumeAllButton, cancelAllBtn, clearAllButton, compactViewButton)) {
            button.setVisible(show); button.setManaged(show);
        }
    }

    /** Shared by Back and window close, so drafts cannot be lost silently. */
    public boolean leaveSettings() {
        if (settingsPage == null) return true;
        if (!settingsPage.confirmLeave(ownerWindow())) return false;
        root.setCenter(homeContent);
        settingsPage = null; homeContent = null;
        settingsButton.setGraphic(settingsGraphic); settingsGraphic = null;
        settingsButton.setAccessibleText("Settings");
        if (hoverTooltipService != null) hoverTooltipService.install(settingsButton, "Settings");
        showHomeToolbar(true);
        return true;
    }

    @FXML
    public void onCompactView(ActionEvent event) {
        enterCompactView();
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

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        if (downloadScheduler != null) downloadScheduler.close();
        // Stop feedback before pauseAll: closing the app is intentionally silent.
        if (downloadSounds != null) downloadSounds.close();
        if (soundFeedback != null) soundFeedback.close();
        try {
            if (downloadStateCoordinator != null) downloadStateCoordinator.pauseAll();
        } catch (Exception ignored) {}
        try { historyService.saveNow(); } catch (Exception ignored) {}
        try {
            if (partialRetention != null) partialRetention.stop();
            if (downloadMonitoringService != null) downloadMonitoringService.stop();
        } catch (Exception ignored) {}
        try { if (browserBridgeService != null) browserBridgeService.close(); } catch (Exception ignored) {}
        try { playlistProbeScheduler.shutdown(); } catch (Exception ignored) {}
        try { videoSizeService.shutdown(); } catch (Exception ignored) {}
        try { if (searchAnimation != null) searchAnimation.stop(); } catch (Exception ignored) {}
        try { UI_DELAY_EXEC.shutdownNow(); } catch (Exception ignored) {}
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
        downloadScheduler.request(row, resume);
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
