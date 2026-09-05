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

    private HoverTooltipService hoverTooltipService;

    private final AtomicLong downloadOrderSeq = new AtomicLong(0);

    private SidebarService sidebarService;
    private DownloadMonitoringService downloadMonitoringService;
    private GlobalSpeedService globalSpeedService;
    private BrowserBridgeService browserBridgeService;
    private Timeline searchAnimation;
    private boolean searchExpanded;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private boolean compactView;
    private Node fullTop;
    private Node fullCenter;
    private Node fullBottom;
    private VBox compactRoot;
    private javafx.stage.Stage compactStage;
    private javafx.stage.Stage fullStage;
    private double compactDragX;
    private double compactDragY;
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
    private double fullWidth;
    private double fullHeight;
    private double fullMinWidth;
    private double fullMinHeight;
    private double fullX;
    private double fullY;
    private boolean fullAlwaysOnTop;
    private boolean fullMaximized;
    private boolean fullFullScreen;
    private double windowedWidth = Double.NaN;
    private double windowedHeight = Double.NaN;
    private double windowedX = Double.NaN;
    private double windowedY = Double.NaN;
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

        historyService.loadOnce();
        initializePlaylistServices();
        initializeAddLink();
        initializeBrowserBridge();
        initializeMonitoring();
    }

    private IconButtonService initializeWindowUi() {
        Platform.runLater(() -> ScrollbarAutoHide.enableGlobalAutoHide(root));
        Platform.runLater(this::initializeStageBoundsTracking);
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

    private void initializeStageBoundsTracking() {
        if (root == null || root.getScene() == null
                || !(root.getScene().getWindow() instanceof javafx.stage.Stage stage)) return;
        Runnable capture = () -> captureWindowedBounds(stage);
        stage.xProperty().addListener(observable -> capture.run());
        stage.yProperty().addListener(observable -> capture.run());
        stage.widthProperty().addListener(observable -> capture.run());
        stage.heightProperty().addListener(observable -> capture.run());
        stage.maximizedProperty().addListener(observable -> capture.run());
        stage.fullScreenProperty().addListener(observable -> capture.run());
        capture.run();
    }

    private void captureWindowedBounds(javafx.stage.Stage stage) {
        if (stage == null || compactView || compactTransitioning
                || stage.isMaximized() || stage.isFullScreen()) return;
        if (stage.getWidth() > 0 && stage.getHeight() > 0) {
            windowedWidth = stage.getWidth();
            windowedHeight = stage.getHeight();
            windowedX = stage.getX();
            windowedY = stage.getY();
        }
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
        compactList.setCellFactory(list -> new CompactDownloadRowCell(
                downloadStateCoordinator::pause,
                downloadStateCoordinator::resume,
                downloadStateCoordinator::cancel,
                iconButtons::installTooltip,
                (node, text) -> hoverTooltipService.install(node, text)
        ));

        Button restoreButton = new Button();
        IconButtonService.setupSvgButton(restoreButton, IconButtonService.FULL_VIEW);
        restoreButton.getStyleClass().add("gx-compact-restore");
        iconButtons.installTooltip(restoreButton, "Full View");
        restoreButton.setOnAction(event -> exitCompactView());

        Button closeButton = new Button("×");
        Button minimizeButton = new Button("−");
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
        windowControls.getStyleClass().add("gx-window-controls");

        BorderPane compactToolbar = new BorderPane();
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        compactToolbar.setLeft(mac ? windowControls : restoreButton);
        compactToolbar.setRight(mac ? restoreButton : windowControls);
        compactToolbar.getStyleClass().add("gx-compact-toolbar");
        compactToolbar.setPickOnBounds(true);
        compactToolbar.setOnMousePressed(event -> {
            compactDragX = event.getSceneX();
            compactDragY = event.getSceneY();
        });
        compactToolbar.setOnMouseDragged(event -> {
            if (compactStage == null) return;
            compactStage.setX(event.getScreenX() - compactDragX);
            compactStage.setY(event.getScreenY() - compactDragY);
        });

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

        compactRoot = new VBox(6, compactToolbar, compactList, compactFooter);
        compactRoot.getStyleClass().add("gx-compact-root");
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
            else compactRoot.setCursor(javafx.scene.Cursor.DEFAULT);
        });
        compactRoot.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
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
            if (event.getSceneY() > 62 || event.getTarget() instanceof Button) return;
            compactDragX = event.getSceneX();
            compactDragY = event.getSceneY();
        });
        compactRoot.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, event -> {
            if (compactResizing && compactStage != null) {
                double dx = event.getScreenX() - compactResizeScreenX;
                double dy = event.getScreenY() - compactResizeScreenY;
                if (compactResizeLeft || compactResizeRight) {
                    double requestedWidth = compactResizeWidth + (compactResizeLeft ? -dx : dx);
                    double newWidth = Math.max(350, Math.min(390, requestedWidth));
                    if (compactResizeLeft) {
                        compactStage.setX(compactResizeStageX + compactResizeWidth - newWidth);
                    }
                    compactStage.setWidth(newWidth);
                }
                if (compactResizeTop || compactResizeBottom) {
                    double requestedHeight = compactResizeHeight + (compactResizeTop ? -dy : dy);
                    double newHeight = Math.max(270, Math.min(290, requestedHeight));
                    if (compactResizeTop) {
                        compactStage.setY(compactResizeStageY + compactResizeHeight - newHeight);
                    }
                    compactStage.setHeight(newHeight);
                }
                event.consume();
                return;
            }
            if (event.getSceneY() > 80 || compactStage == null || event.getTarget() instanceof Button) return;
            compactStage.setX(event.getScreenX() - compactDragX);
            compactStage.setY(event.getScreenY() - compactDragY);
        });
        compactRoot.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> {
            compactResizing = false;
            compactResizeLeft = compactResizeRight = compactResizeTop = compactResizeBottom = false;
        });
        VBox.setVgrow(compactList, Priority.ALWAYS);
    }

    private void enterCompactView() {
        if (compactView || compactTransitioning || root == null
                || compactRoot == null || root.getScene() == null) return;
        javafx.stage.Window window = root.getScene().getWindow();
        if (!(window instanceof javafx.stage.Stage stage)) return;
        fullStage = stage;

        captureWindowedBounds(stage);
        compactView = true;
        fullTop = root.getTop();
        fullCenter = root.getCenter();
        fullBottom = root.getBottom();
        fullWidth = stage.getWidth();
        fullHeight = stage.getHeight();
        fullMinWidth = stage.getMinWidth();
        fullMinHeight = stage.getMinHeight();
        fullX = stage.getX();
        fullY = stage.getY();
        fullAlwaysOnTop = stage.isAlwaysOnTop();
        fullMaximized = stage.isMaximized();
        fullFullScreen = stage.isFullScreen();

        double compactWidth = 390;
        double compactHeight = 290;
        if (compactStage == null) {
            compactStage = new javafx.stage.Stage(javafx.stage.StageStyle.TRANSPARENT);
            javafx.scene.Scene compactScene = new javafx.scene.Scene(compactRoot, compactWidth, compactHeight);
            compactScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            // The theme is declared on the FXML root rather than the original
            // Scene, so copy both sources when Compact View gets its own Scene.
            compactScene.getStylesheets().setAll(root.getScene().getStylesheets());
            compactScene.getStylesheets().addAll(root.getStylesheets());
            compactStage.setScene(compactScene);
            compactStage.setMinWidth(350);
            compactStage.setMaxWidth(compactWidth);
            compactStage.setMinHeight(270);
            compactStage.setMaxHeight(compactHeight);
            compactStage.setAlwaysOnTop(true);
        }
        compactStage.setWidth(compactWidth);
        compactStage.setHeight(compactHeight);
        compactStage.setX(fullX + Math.max(0, (fullWidth - compactWidth) / 2));
        compactStage.setY(fullY + Math.max(0, (fullHeight - compactHeight) / 2));
        stage.hide();
        compactStage.show();
        if (compactStage.getProperties().putIfAbsent("grabx-smart-scroll", Boolean.TRUE) == null) {
            Platform.runLater(() -> ScrollbarAutoHide.enableGlobalAutoHide(compactRoot));
        }
        compactStage.toFront();
    }

    private void exitCompactView() {
        if (!compactView || compactTransitioning || root == null || root.getScene() == null) return;
        javafx.stage.Stage stage = fullStage;
        if (stage == null) return;
        if (compactStage != null) compactStage.hide();
        stage.show();
        if (!fullMaximized && !fullFullScreen) {
            stage.setWidth(fullWidth);
            stage.setHeight(fullHeight);
            stage.setX(fullX);
            stage.setY(fullY);
        }
        stage.setMaximized(fullMaximized);
        stage.setFullScreen(fullFullScreen);
        stage.toFront();
        compactView = false;
        windowedWidth = fullWidth;
        windowedHeight = fullHeight;
        windowedX = fullX;
        windowedY = fullY;
    }

    private void transitionStage(javafx.stage.Stage stage, Runnable applyLayout) {
        compactTransitioning = true;
        Timeline fadeOut = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(stage.opacityProperty(), stage.getOpacity())),
                new KeyFrame(Duration.millis(80), new KeyValue(stage.opacityProperty(), 0.0))
        );
        fadeOut.setOnFinished(event -> {
            applyLayout.run();
            root.applyCss();
            root.layout();
            Timeline fadeIn = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(stage.opacityProperty(), 0.0)),
                    new KeyFrame(Duration.millis(130), new KeyValue(stage.opacityProperty(), 1.0))
            );
            fadeIn.setOnFinished(done -> compactTransitioning = false);
            fadeIn.play();
        });
        fadeOut.play();
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
                stage.show();
                stage.toFront();
                stage.requestFocus();
            }
            if (statusText != null) statusText.setText("Received from browser: " + capture.title());
            if ("file".equalsIgnoreCase(capture.action()) && downloadQueueService != null) {
                String folder = capture.suggestedFolder();
                if (folder == null || folder.isBlank()) {
                    folder = downloadFolderPreferences.getLastFolderOrDefault();
                }
                String filename = capture.suggestedFilename();
                if (filename == null || filename.isBlank()) filename = capture.title();
                downloadQueueService.enqueueDirect(capture.effectiveUrl(), folder, filename, capture.pageUrl());
                if (statusText != null) statusText.setText("Browser download started in GrabX");
            } else if (addLinkFlowService != null) {
                addLinkFlowService.openOrUpdate(
                        capture.effectiveUrl(), capture.action(), true, capture.suggestedFolder());
            }
        } catch (Exception ignored) {
        }
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
        try {
            if (downloadStateCoordinator != null) downloadStateCoordinator.pauseAll();
        } catch (Exception ignored) {}
        try { historyService.saveNow(); } catch (Exception ignored) {}
        try {
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