package com.grabx.app.grabx;
import com.grabx.app.grabx.ui.components.DownloadRowActions;
import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.core.service.DownloadStateCoordinator;
import com.grabx.app.grabx.core.service.ClearAllService;
import com.grabx.app.grabx.core.service.PlaylistBatchService;
import com.grabx.app.grabx.ui.components.HoverBubble;
import com.grabx.app.grabx.ui.components.NoSelectionModel;
import com.grabx.app.grabx.ui.dialogs.NativeDialogs;
import com.grabx.app.grabx.thumbs.ThumbnailCacheManager;
import com.grabx.app.grabx.core.service.DownloadRunner;
import com.grabx.app.grabx.util.YtDlpManager;
import javafx.animation.*;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import com.grabx.app.grabx.core.service.HoverTooltipService;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.grabx.app.grabx.ui.components.ScrollbarAutoHide;
import com.grabx.app.grabx.ui.sidebar.SidebarItem;
import com.grabx.app.grabx.ui.playlist.PlaylistEntry;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.File;

import static com.grabx.app.grabx.util.YtDlpManager.*;
import com.grabx.app.grabx.ui.components.DownloadRowCell;

public class MainController {
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

    private Dialog<ButtonType> activeAddLinkDialog = null;

    @FXML
    private ListView<DownloadRow> downloadsList;


    // fields in MainController
    private final java.util.ArrayDeque<PlaylistEntry> playlistDownloadQueue = new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicBoolean playlistBatchRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String playlistBatchMode = MODE_VIDEO;
    private volatile String playlistBatchDefaultQuality = QUALITY_BEST;

    private PlaylistBatchService playlistBatchService;

    private com.grabx.app.grabx.core.service.AddLinkDialogService addLinkDialogService;

    private HoverTooltipService hoverTooltipService;

    private final java.util.concurrent.atomic.AtomicLong downloadOrderSeq =
            new java.util.concurrent.atomic.AtomicLong(0);

    // فوق مع حقول الكلاس
    private final java.util.concurrent.ConcurrentHashMap<String, DownloadRow> playlistRowByVideoId =
            new java.util.concurrent.ConcurrentHashMap<>();


    private com.grabx.app.grabx.core.service.ClipboardService clipboardService;


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



    private static String pKey(String vid, String mode, String q) {
        String m = (mode == null || mode.isBlank()) ? MODE_VIDEO : mode;
        String qq = (q == null || q.isBlank()) ? QUALITY_BEST : q;
        return vid + "|" + m + "|" + qq;
    }



    private static final Map<String, ProbeQualitiesResult> PLAYLIST_PROBE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Image> PLAYLIST_THUMB_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PLAYLIST_PROBE_INFLIGHT = ConcurrentHashMap.newKeySet();
    private static final Set<String> PLAYLIST_THUMB_INFLIGHT = ConcurrentHashMap.newKeySet();

    private final java.util.Map<DownloadRow, Process> activeProcesses = new java.util.concurrent.ConcurrentHashMap<>();

    private DownloadStateCoordinator downloadStateCoordinator;
    private DownloadRunner downloadRunner;
    private final java.util.concurrent.ConcurrentHashMap<DownloadRow, Double> lastProgressMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.Map<DownloadRow, String> stopReasons = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String YTDLP_OUT_TMPL = "%(title)s.%(ext)s";


    private com.grabx.app.grabx.core.service.MissingWatcherService missingWatcherService;

    // Dynamic Sidebar item for Missing (show only if needed)
    private final SidebarItem SIDEBAR_MISSING_ITEM = new SidebarItem("MISSING", "Missing");
    // =====================
    // Download history (JSON) – lightweight persistence
    // =====================
    private static final Path HISTORY_DIR  = Paths.get(System.getProperty("user.home"), ".grabx");
    private static final Path HISTORY_FILE = HISTORY_DIR.resolve("download_history.json");
    private static final int HISTORY_MAX_ITEMS = 500; // لاحقاً: user setting

    // ========== Add Link dialog state tracking for clipboard auto-paste ==========
    private boolean addLinkDialogOpen = false;
    private TextField activeAddLinkUrlField = null;
    private String pendingAddLinkPrefillUrl = null;

    // Keep a strong reference so the poll Timeline doesn't get GC'ed
    private javafx.animation.Timeline clipboardPollTimeline;

    // Persist last selected download folder
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainController.class);

    private final ObservableList<DownloadRow> downloadItems = FXCollections.observableArrayList();

    //  ===========================
    //  ========= Classes =========
    //  ===========================
    private final com.grabx.app.grabx.core.service.DownloadService downloadService =
            new com.grabx.app.grabx.core.service.DownloadService(downloadItems);
    private final ClearAllService clearAllService = new ClearAllService(downloadItems);

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
                    this::reconcileLoadedRowsWithDisk,
                    this::updateMissingSidebarItem,
                    this::warmMissingThumbnailsAsync,
                    this::thumbFromUrl
            );


    //  ===========================


    // Current sidebar filter key (combined with searchField filter)
    private volatile String currentSidebarFilterKey = "ALL";

    // ========= In-scene hover tooltip (no jitter) =========

    public static final String QUALITY_BEST = "Best quality (Recommended)";
    public static final String QUALITY_SEPARATOR = "──────────────";
    private static final String QUALITY_CUSTOM = "Custom (Mixed)";
    private static final String MODE_VIDEO = "Video";
    public static final String MODE_AUDIO = "Audio only";

    public static final String AUDIO_BEST = "Best audio (Recommended)";
    public static final String AUDIO_DEFAULT_FORMAT = "mp3";
    private static final List<String> AUDIO_FORMATS = List.of(
            "m4a", "mp3", "opus", "aac", "wav", "flac"
    );

    private volatile boolean reopenAddLinkAfterPlaylist = false;
    private volatile String reopenAddLinkPrefillUrl = null;

    // ========= Playlist probing (IMPORTANT: limit concurrency to avoid freezing on large playlists) =========
    private static final int PLAYLIST_PROBE_THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));


    private static final ExecutorService PLAYLIST_PROBE_EXEC = new ThreadPoolExecutor(
            PLAYLIST_PROBE_THREADS,
            PLAYLIST_PROBE_THREADS,
            30L,
            TimeUnit.SECONDS,
            // bounded queue so we don't enqueue unlimited yt-dlp jobs
            new LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "playlist-probe");
                t.setDaemon(true);
                return t;
            },

            new ThreadPoolExecutor.AbortPolicy()
    );

    // ========= Analyze URL (backend logic - v1) =========
    private enum ContentType {
        DIRECT_FILE,
        VIDEO,
        PLAYLIST,
        UNSUPPORTED
    }

    private static final String[] DIRECT_EXT = {
            ".zip", ".rar", ".7z", ".tar", ".gz",
            ".pdf", ".epub",
            ".exe", ".dmg", ".pkg",
            ".iso",
            ".mp3", ".wav", ".m4a", ".flac",
            ".mp4", ".mkv", ".mov", ".webm",
            ".jpg", ".jpeg", ".png", ".gif", ".webp"
    };


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
                                    currentSidebarFilterKey,
                                    (searchField == null ? "" : searchField.getText())
                            );
                        }
                    } catch (Exception ignored) {}
                }
        );

        downloadRunner = new DownloadRunner(
                activeProcesses,
                stopReasons,
                lastProgressMap,
                () -> {
                    try { if (historyService != null) historyService.scheduleSave(); } catch (Exception ignored) {}
                },
                this::updateMissingSidebarItem,
                () -> {
                    try {
                        if (downloadService != null) {
                            downloadService.setCombinedFilter(
                                    currentSidebarFilterKey,
                                    (searchField == null ? "" : searchField.getText())
                            );
                        }
                    } catch (Exception ignored) {}
                },
                label -> parseHeightFromLabel(String.valueOf(label)),
                (yt, url, selector, outDir, outTpl) -> probeOutputFilename(yt, url, selector, outDir, outTpl),
                fmt -> supportsAudioThumbnailEmbedding(fmt),
                line -> isAudioStreamFromDestinationLine(line),
                value -> parseLongSafe(value),
                bytes -> formatBytesDecimal(bytes),
                value -> normalizeSpeedUnit(value),
                (row, progress) -> applyProgressMonotonic(row, progress),
                process -> killProcessTree(process),
                MODE_AUDIO,
                QUALITY_BEST,
                QUALITY_SEPARATOR,
                AUDIO_BEST,
                AUDIO_DEFAULT_FORMAT
        );

        // Main downloads list (center) - initialize after coordinator is ready
        ensureDownloadsListView();

        applyFilter("ALL");
        setupSearchFilter();
        historyService.loadOnce();
        initPlaylistBatchService();


        try {
            if (addLinkDialogService == null && root != null) {
                addLinkDialogService =
                        new com.grabx.app.grabx.core.service.AddLinkDialogService(
                                root,
                                UI_DELAY_EXEC,
                                new com.grabx.app.grabx.core.service.AddLinkDialogService.Callbacks() {
                                    @Override public void installClickToDefocus(DialogPane pane) { MainController.this.installClickToDefocus(pane); }
                                    @Override public void bringWindowToFront(javafx.stage.Window w) { MainController.this.bringWindowToFront(w); }

                                    @Override public boolean isHttpUrl(String s) { return MainController.this.isHttpUrl(s); }
                                    @Override public String shorten(String s) { return MainController.this.shorten(s); }

                                    @Override public String getLastDownloadFolderOrDefault() { return MainController.this.getLastDownloadFolderOrDefault(); }
                                    @Override public void saveLastDownloadFolder(String folder) { MainController.this.saveLastDownloadFolder(folder); }

                                    @Override public void addDownloadItemToList(String url, String folder, String mode, String quality) {
                                        MainController.this.addDownloadItemToList(url, folder, mode, quality);
                                    }

                                    @Override public void onPlaylistDetected(String playlistUrl, String folder) {
                                        // نفس سلوكك الحالي: flags + open playlist
                                        reopenAddLinkAfterPlaylist = true;
                                        reopenAddLinkPrefillUrl = playlistUrl;

                                        try { if (activeAddLinkDialog != null) activeAddLinkDialog.hide(); } catch (Exception ignored) {}
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
                                    currentSidebarFilterKey,
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
                    this::openOrUpdateAddLinkDialog,  // ✅ يفتح الديالوج ومعه الرابط
                    url -> {
                        // ✅ لو الديالوج مفتوح: حدّث الحقل فورًا
                        try { pendingAddLinkPrefillUrl = url; } catch (Exception ignored) {}
                        if (addLinkDialogOpen && activeAddLinkUrlField != null) {
                            try {
                                activeAddLinkUrlField.setText(url);
                                activeAddLinkUrlField.positionCaret(url.length());
                            } catch (Exception ignored) {}
                        }
                    },
                    () -> addLinkDialogOpen
            );
            clipboardService.start();
        } catch (Exception ignored) {}
        // + button: open Add Link and prefill from clipboard if URL
        if (addLinkButton != null) {
            addLinkButton.setOnAction(ev -> openAddLinkFromClipboardOrEmpty());
        }
        // Sidebar
        sidebarList.getItems().setAll(
                new SidebarItem("ALL", "All"),
                new SidebarItem("DOWNLOADING", "Downloading"),
                new SidebarItem("PAUSED", "Paused"),
                new SidebarItem("COMPLETED", "Completed"),
                new SidebarItem("CANCELLED", "Cancelled")
        );
        sidebarList.setFixedCellSize(44);
        sidebarList.setPrefHeight(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
        sidebarList.setMaxHeight(Double.MAX_VALUE);

        sidebarList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SidebarItem item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getTitle());
            }
        });

        sidebarList.getSelectionModel().selectFirst();

        SidebarItem first = sidebarList.getSelectionModel().getSelectedItem();
        if (contentTitle != null && first != null) contentTitle.setText(first.getTitle());

        sidebarList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;

            if (contentTitle != null) contentTitle.setText(newV.getTitle());
            if (statusText != null) statusText.setText("Filter: " + newV.getTitle());

            applyFilter(newV.getKey());
        });

        Platform.runLater(() -> {
            String clip = readClipboardTextSafe();
            if (!isHttpUrl(clip)) return;

            lastClipboardText = clip;

            UI_DELAY_EXEC.schedule(() -> Platform.runLater(() -> openAddLinkDialogDeferred(clip)),
                    350, TimeUnit.MILLISECONDS);
        });
    }

    private void initPlaylistBatchService() {
        try {
            PlaylistBatchService.Callbacks cb = new PlaylistBatchService.Callbacks();

            // videoId -> watch URL
            cb.youtubeWatchUrl = MainController::youtubeWatchUrl;

            // create a PENDING row and add it to main list (no engine start here)
            cb.addPendingRow = (url, mode, quality, title) -> {
                DownloadRow r = createDownloadRow(url, mode, quality, title);
                try { r.state.set(DownloadRow.State.QUEUED); } catch (Exception ignored) {}

                // ✅ apply thumbnail immediately for playlist rows
                try { applyThumbForRow(r, url); } catch (Exception ignored) {}

                Platform.runLater(() -> {
                    try {
                        downloadItems.add(r);
                        // keep filters/history consistent
                        try { downloadService.setCombinedFilter(currentSidebarFilterKey, (searchField == null ? "" : searchField.getText())); } catch (Exception ignored) {}
                        try { if (historyService != null) historyService.attachAutoSave(r); } catch (Exception ignored) {}
                    } catch (Exception ignored) {}
                });
                return r;
            };

            // start download on an existing row
            cb.startDownloadRow = (row) -> {
                if (row == null) return;
                Platform.runLater(() -> {
                    try { startDownloadRow(row, false); } catch (Exception ignored) {}
                });
            };

            // fallback: create row + add + start immediately
            cb.startDownloadForUrl = (url, mode, quality, title) -> {
                DownloadRow r = createDownloadRow(url, mode, quality, title);

                // ✅ apply thumbnail immediately for playlist rows
                try { applyThumbForRow(r, url); } catch (Exception ignored) {}

                Platform.runLater(() -> {
                    try {
                        downloadItems.add(r);
                        try { downloadService.setCombinedFilter(currentSidebarFilterKey, (searchField == null ? "" : searchField.getText())); } catch (Exception ignored) {}
                        try { if (historyService != null) historyService.attachAutoSave(r); } catch (Exception ignored) {}
                        try { startDownloadRow(r, false); } catch (Exception ignored) {}
                    } catch (Exception ignored) {}
                });
                return r;
            };

            // extract youtube id from URL
            cb.extractYoutubeId = MainController::extractYouTubeId;

            // persist history after adding rows
            cb.scheduleHistorySave = () -> {
                try { if (historyService != null) historyService.scheduleSave(); } catch (Exception ignored) {}
            };

            // status text
            cb.setStatusText = (txt) -> {
                if (statusText != null && txt != null) {
                    Platform.runLater(() -> statusText.setText(txt));
                }
            };

            playlistBatchService = new PlaylistBatchService(cb);

        } catch (Exception ex) {
            playlistBatchService = null; // خليه يشتغل حتى لو السيرفس مش جاهز
        }
    }

    private static boolean probeVideoQualitiesAsync(
            String videoUrl,
            String videoId,
            java.util.function.Consumer<ProbeQualitiesResult> onDone
    ) {
        if (videoUrl == null || videoUrl.isBlank() || videoId == null || videoId.isBlank()) return false;

        // cache hit
        ProbeQualitiesResult cached = PLAYLIST_PROBE_CACHE.get(videoId);
        if (cached != null) {
            Platform.runLater(() -> onDone.accept(cached));
            return true;
        }

        // avoid duplicate in-flight probes for same item
        if (!PLAYLIST_PROBE_INFLIGHT.add(videoId)) return true;

        try {
            PLAYLIST_PROBE_EXEC.execute(() -> {
                try {
                    ProbeQualitiesResult pr = probeQualitiesHeightsOnly(videoUrl);
                    PLAYLIST_PROBE_CACHE.put(videoId, pr);
                    Platform.runLater(() -> onDone.accept(pr));
                } finally {
                    PLAYLIST_PROBE_INFLIGHT.remove(videoId);
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            // queue full -> caller should retry later
            PLAYLIST_PROBE_INFLIGHT.remove(videoId);
            return false;
        }
    }

    /**
     * Lightweight probe for playlist UI: detect available heights only (NO size probing).
     * This avoids the very slow yt-dlp call: --skip-download ... --print %(filesize,filesize_approx)s
     */
    private static ProbeQualitiesResult probeQualitiesHeightsOnly(String url) {
        long now = System.currentTimeMillis();
        Set<Integer> heights = new HashSet<>();
        Map<Integer, String> sizeByHeight = new HashMap<>();
        Map<Integer, Long> bytesByHeight = new HashMap<>();

        if (url == null || url.isBlank()) {
            return new ProbeQualitiesResult(heights, bytesByHeight, sizeByHeight, -1L, now);
        }

        try {
            heights = probeHeightsFastJson(url);
            heights = normalizeHeights(heights);
        } catch (Exception ignored) {}

        // bestBytes intentionally unknown here
        return new ProbeQualitiesResult(heights, bytesByHeight, sizeByHeight, -1L, now);
    }


    // ========= Actions =========
    @FXML
    public void onAddLink(ActionEvent event) {
        String clip = readClipboardTextSafe();
        clip = (clip == null) ? null : clip.trim();
        showAddLinkDialog(isHttpUrl(clip) ? clip : null);
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
                        currentSidebarFilterKey,
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

    // Matches: 1080p, 1080p60, 2160p, 2160p60 ... (yt-dlp often prints p60 without WxH)
    private static final Pattern YTDLP_HEIGHT_P = Pattern.compile("\\b(\\d{3,4})p(?:\\d{1,3})?\\b");

    // Matches: 1920x1080, 3840x2160 ...
    private static final Pattern YTDLP_HEIGHT_X = Pattern.compile("\\b\\d{3,4}x(\\d{3,4})\\b");


    private static VideoInfo probeOnceFast(String url) {
        long t = tStart("probeOnceFast", url);

        try {
            String json = YtDlpManager.run(List.of(
                    "-J",
                    "--no-playlist",
                    "--no-warnings",
                    url
            ));

            if (json == null || json.isBlank()) return null;
            return parseVideoInfoFast(json);

        } catch (Exception e) {
            return null;
        } finally {
            tEnd("probeOnceFast", t);
        }
    }

    private static VideoInfo parseVideoInfoFast(String json) {
        VideoInfo info = new VideoInfo();

        try {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = om.readTree(json);
            var formats = root.get("formats");
            if (formats == null || !formats.isArray()) return info;

            for (var f : formats) {
                if (!f.has("height")) continue;
                int h = f.get("height").asInt(-1);
                if (h > 0) info.heights.add(normalizeHeight(h));
            }

            // best = أعلى ارتفاع
            if (!info.heights.isEmpty()) {
                info.bestBytes = -1;
            }

        } catch (Exception ignored) {}

        return info;
    }

    static class VideoInfo {
        Set<Integer> heights = new TreeSet<>();
        Map<Integer, Long> sizeByHeight = new HashMap<>();
        long bestBytes = -1;
    }


    private static Set<Integer> probeHeightsFastJson(String url) {
        Set<Integer> heights = new HashSet<>();
        if (url == null || url.isBlank()) return heights;

        try {
            List<String> args = List.of(
                    "--no-warnings",
                    "--no-playlist",
                    "-J",
                    "--encoding", "utf-8",
                    url.trim()
            );

            String json = com.grabx.app.grabx.util.YtDlpManager.run(args);
            if (json == null) return heights;

            // Sometimes yt-dlp may emit non-JSON lines (network errors, warnings, etc.).
            // Keep only the first JSON object if possible.
            int firstBrace = json.indexOf('{');
            if (firstBrace > 0) json = json.substring(firstBrace);
            if (json.isBlank() || !json.trim().startsWith("{")) return heights;

            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            var root = om.readTree(json);
            var formats = root.get("formats");
            if (formats == null || !formats.isArray()) return heights;

            for (var f : formats) {
                if (!f.has("height")) continue;
                int h = f.get("height").asInt(-1);
                int nh = normalizeHeight(h);
                if (nh > 0) heights.add(nh);
            }
        } catch (Exception ignored) {}

        return normalizeHeights(heights);
    }

    private static final Pattern YTDLP_SIZE = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)(KiB|MiB|GiB)\\b");

    private static String youtubeWatchUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    // ===== Thumbnail helpers (YouTube) =====
    private static String extractYouTubeId(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;

        // youtu.be/<id>
        int yi = u.indexOf("youtu.be/");
        if (yi >= 0) {
            String tail = u.substring(yi + "youtu.be/".length());
            int q = tail.indexOf('?');
            if (q >= 0) tail = tail.substring(0, q);
            int a = tail.indexOf('&');
            if (a >= 0) tail = tail.substring(0, a);
            if (!tail.isBlank()) return tail;
        }

        // youtube.com/watch?v=<id>
        int vi = u.indexOf("v=");
        if (vi >= 0) {
            String tail = u.substring(vi + 2);
            int a = tail.indexOf('&');
            if (a >= 0) tail = tail.substring(0, a);
            int h = tail.indexOf('#');
            if (h >= 0) tail = tail.substring(0, h);
            if (!tail.isBlank()) return tail;
        }

        return null;
    }

    private static String buildYouTubeThumbUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        // Stable CDN; hqdefault works well for list thumbnails
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }


    private static String approxSizeTextFromLine(String line) {
        if (line == null) return null;
        Matcher ms = YTDLP_SIZE.matcher(line);
        if (!ms.find()) return null;

        double v = safeParseDouble(ms.group(1));
        String unit = ms.group(2);

        // Convert to MB (approx)
        double mb;
        switch (unit) {
            case "GiB" -> mb = v * 1024.0 * 1.048576;
            case "MiB" -> mb = v * 1.048576;
            case "KiB" -> mb = v * 1024.0 / 1_000_000.0;
            default -> mb = v;
        }
        return formatApproxSize(mb);
    }

    private static double safeParseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String formatApproxSize(double mb) {
        if (mb <= 0) return null;
        if (mb >= 1024) {
            double gb = mb / 1024.0;
            return String.format(" %.1f GB", gb);
        }
        return Math.round(mb) + " MB";
    }

    private static String buildMetaLine(PlaylistEntry it) {
        if (it == null) return "";
        String q = it.getQuality();
        String sz = it.getSizeForQuality(q);
        if (sz == null || sz.isBlank()) return q;        // Best quality غالبًا بدون حجم
        return q + " \u2022 " + sz; // "•"
    }

    public static int parseHeightFromLabel(String label) {
        if (label == null) return -1;
        Matcher mp = YTDLP_HEIGHT_P.matcher(label);
        if (mp.find()) return safeParseInt(mp.group(1));
        return -1;
    }

    /**
     * Pick closest supported quality for THIS item based on desired label.
     * - BEST => BEST
     * - 1080p => choose highest available <=1080; if none, choose highest available
     */
    private static String pickClosestSupportedQuality(String desired, java.util.List<String> availableLabels) {
        if (desired == null || desired.isBlank()) return QUALITY_BEST;
        if (QUALITY_BEST.equals(desired)) return QUALITY_BEST;

        int desiredH = parseHeightFromLabel(desired);
        if (desiredH <= 0) return QUALITY_BEST;

        if (availableLabels == null || availableLabels.isEmpty()) return QUALITY_BEST;

        java.util.List<Integer> hs = new java.util.ArrayList<>();
        java.util.Map<Integer, String> labelByH = new java.util.HashMap<>();

        for (String s : availableLabels) {
            if (s == null) continue;
            if (QUALITY_SEPARATOR.equals(s)) continue;
            if (QUALITY_BEST.equals(s)) continue;
            int h = parseHeightFromLabel(s);
            if (h > 0) {
                hs.add(h);
                labelByH.put(h, s);
            }
        }

        if (hs.isEmpty()) return QUALITY_BEST;

        hs.sort(java.util.Comparator.naturalOrder());

        Integer bestLE = null;
        for (Integer h : hs) {
            if (h <= desiredH) bestLE = h;
        }

        if (bestLE != null) return labelByH.get(bestLE);

        Integer max = hs.get(hs.size() - 1);
        return labelByH.get(max);
    }



    private static ProbeQualitiesResult probeQualitiesWithSizes(String url) {
        long now = System.currentTimeMillis();

        // ===== Cache hit =====
        try {
            ProbeQualitiesResult cached = VIDEO_INFO_CACHE.get(url);
            if (cached != null && cached.isFresh()) {
                return cached;
            }
        } catch (Exception ignored) {}

        Set<Integer> heights = new HashSet<>();
        Map<Integer, String> sizeByHeight = new HashMap<>();
        Map<Integer, Long> bytesByHeight = new HashMap<>();

        if (url == null || url.isBlank()) {
            return new ProbeQualitiesResult(heights, bytesByHeight, sizeByHeight, -1L, now);
        }

        // ===== 1) Detect available heights ONCE (FAST, JSON only) =====
        heights = probeHeightsFastJson(url);
        heights = normalizeHeights(heights);

        // ===== 2) Compute BEST size ONLY (blocking, once) =====
        Integer bestH = null;
        for (Integer h : heights) {
            if (h == null || h <= 0) continue;
            if (bestH == null || h > bestH) bestH = h;
        }

        long bestBytes = -1L;
        if (bestH != null) {
            try {
                String selector = buildFormatSelectorForHeight(bestH);
                Long b = fetchCombinedSizeBytesWithYtDlpPrint(url, selector);
                if (b != null && b > 0) {
                    bestBytes = b;

                    bytesByHeight.put(bestH, b);
                    sizeByHeight.put(bestH, formatBytesDecimal(b));

                    // seed cache for instant UI usage
                    SIZE_CACHE.put(url + "|" + MODE_VIDEO + "|" + QUALITY_BEST, b);
                    SIZE_CACHE.put(url + "|" + MODE_VIDEO + "|" + formatHeightLabel(bestH), b);
                }
            } catch (Exception ignored) {}
        }

        ProbeQualitiesResult pr =
                new ProbeQualitiesResult(heights, bytesByHeight, sizeByHeight, bestBytes, now);

        try {
            VIDEO_INFO_CACHE.put(url, pr);
        } catch (Exception ignored) {}

        return pr;
    }

    /** selector like: bv*[height<=720]+ba/b[height<=720]/bv*+ba/b */
    private static String buildFormatSelectorForHeight(int height) {
        int h = Math.max(1, height);
        return "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/bv*+ba/b";
    }

    /**
     * Prints bytes as integer using yt-dlp template:
     * %(filesize,filesize_approx)s
     */
    private static Long fetchCombinedSizeBytesWithYtDlpPrint(String url, String selector) {
        if (url == null || url.isBlank()) return null;

        try {
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("--no-warnings");
            args.add("--no-playlist");
            args.add("--skip-download");
            args.add("-f"); args.add(selector);
            args.add("--print"); args.add("%(filesize,filesize_approx)s");
            args.add(url.trim());

            // IMPORTANT: use your bundled yt-dlp manager (works on mac/win/linux)
            String out = com.grabx.app.grabx.util.YtDlpManager.run(args);
            if (out == null) return null;

            for (String line : out.split("\\R")) {
                if (line == null) continue;
                String t = line.trim();
                if (t.isEmpty()) continue;

                boolean allDigits = t.chars().allMatch(Character::isDigit);
                if (!allDigits) continue;

                long v = Long.parseLong(t);
                if (v > 0) return v;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }


    private static int safeParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private void applyFilter(String key) {
        // sidebar filter changed
        String k = (key == null) ? "ALL" : key.trim().toUpperCase(java.util.Locale.ROOT);
        currentSidebarFilterKey = k;
        downloadService.setCombinedFilter(currentSidebarFilterKey,
                (searchField == null ? "" : searchField.getText()));
    }

    private void setupSearchFilter() {
        if (searchField == null) return;

        // As-you-type filtering (auto-complete feel)
        searchField.textProperty().addListener((obs, oldV, newV) ->
                downloadService.setCombinedFilter(currentSidebarFilterKey,
                        (searchField == null ? "" : searchField.getText()))        );

        // Optional: ESC clears search quickly
        searchField.setOnKeyPressed(e -> {
            try {
                switch (e.getCode()) {
                    case ESCAPE -> {
                        searchField.clear();
                        e.consume();
                    }
                }
            } catch (Exception ignored) {}
        });
    }



    private static boolean containsAny(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty()) return false;
        if (needle == null || needle.isEmpty()) return true;
        return haystack.contains(needle);
    }

    private static String safeLower(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(java.util.Locale.ROOT);
    }


    // ========= AddLink open helpers (safe showAndWait) =========

    // Small delay helper (avoids calling showAndWait from animation/layout pulses)
    private static final ScheduledExecutorService UI_DELAY_EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-delay");
        t.setDaemon(true);
        return t;
    });

    private void openAddLinkFromClipboardOrEmpty() {
        String clip = readClipboardTextSafe();
        String prefill = isHttpUrl(clip) ? clip.trim() : null;
        openAddLinkDialogDeferred(prefill);
    }

    private void openAddLinkDialogDeferred(String prefillUrl) {
        // Avoid: IllegalStateException: showAndWait is not allowed during animation or layout processing
        UI_DELAY_EXEC.schedule(() -> Platform.runLater(() -> showAddLinkDialog(prefillUrl)),
                80, TimeUnit.MILLISECONDS);
    }

    // open add link page when copy now link
    private void handleClipboardUrl(String url) {
        if (!isHttpUrl(url)) return;

        // إذا نافذة AddLink مفتوحة → حدّث الحقل
        if (addLinkDialogOpen && activeAddLinkUrlField != null) {
            activeAddLinkUrlField.setText(url);
            activeAddLinkUrlField.positionCaret(url.length());
            return;
        }

        // غير مفتوحة → افتح AddLink مع prefill (deferred) بدل fire أثناء layout/animation
        pendingAddLinkPrefillUrl = url;
        openAddLinkDialogDeferred(url);
    }
    private void onClipboardChanged(String newText) {
        handleClipboardUrl(newText);
    }

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

    private void cancelAllActiveDownloads() {
        for (DownloadRow row : downloadItems) {
            DownloadRow.State st = row.state.get();
            if (st == DownloadRow.State.DOWNLOADING
                    || st == DownloadRow.State.PAUSED
                    || st == DownloadRow.State.QUEUED) {

                downloadStateCoordinator.cancel(row);
            }
        }
        if (statusText != null) {
            statusText.setText("All active downloads cancelled");
        }
    }

    private void applyProgressMonotonic(DownloadRow row, double newPct) {
        if (row == null) return;
        if (newPct < 0) return;

        if (newPct > 1.0) newPct = 1.0;

        Double prevObj = lastProgressMap.get(row);
        double prev = (prevObj == null) ? -1.0 : prevObj;

        if (prev < 0) {
            lastProgressMap.put(row, newPct);
            row.progress.set(newPct);
            return;
        }

        // سماحية بسيطة جدًا للـ rounding
        double epsilon = 0.003; // 0.3%
        if (newPct + epsilon < prev) {
            return; // تجاهل الرجعة للخلف
        }

        if (newPct > prev) lastProgressMap.put(row, newPct);

        row.progress.set(Math.max(prev, newPct));
    }





    // ========= Custom in-scene tooltip bubble (no Popup/Tooltip jitter) =========


    private static ContentType analyzeUrlType(String url) {
        if (url == null) return ContentType.UNSUPPORTED;
        String u = url.trim();
        if (u.isEmpty()) return ContentType.UNSUPPORTED;

        // must be a URL-ish string
        String lower = u.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return ContentType.UNSUPPORTED;
        }

        // 1) obvious direct file by extension
        for (String ext : DIRECT_EXT) {
            if (lower.contains(ext + "?") || lower.endsWith(ext)) {
                return ContentType.DIRECT_FILE;
            }
        }

        // 2) YouTube playlist heuristics
        // - playlist url contains "playlist" or "list=" without a specific video id
        boolean hasList = lower.contains("list=");
        boolean looksYouTube = lower.contains("youtube.com") || lower.contains("youtu.be");
        boolean hasVideoId = lower.contains("watch?v=") || lower.contains("youtu.be/");
        boolean looksPlaylistPath = lower.contains("youtube.com/playlist");

        if (looksYouTube && (looksPlaylistPath || (hasList && !hasVideoId))) {
            return ContentType.PLAYLIST;
        }

        // 3) treat other YouTube watch links as single video
        if (looksYouTube && (hasVideoId || hasList)) {
            // note: watch?v=...&list=... is still a single video link; playlist selection will be offered later
            return ContentType.VIDEO;
        }

        // 4) For other sites, we cannot know yet without HEAD/yt-dlp probing.
        // We'll treat it as DIRECT_FILE candidate only after probing in a later iteration.
        return ContentType.DIRECT_FILE;
    }

    private static void setManagedVisible(Node n, boolean visible) {
        if (n == null) return;
        n.setVisible(visible);
        n.setManaged(visible);
    }



    private static java.util.List<String> buildAudioOptions() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        out.add(AUDIO_BEST);
        out.add(QUALITY_SEPARATOR);
        out.addAll(AUDIO_FORMATS);
        return out;
    }

    private static void fillQualityCombo(ComboBox<String> qualityCombo) {
        if (qualityCombo == null) return;

        // Default (safe) list: do NOT show 4K/2K unless we actually detect them for the specific video.
        qualityCombo.getItems().setAll(
                QUALITY_BEST,
                QUALITY_SEPARATOR,
                "1080p",
                "720p",
                "540p",
                "480p",
                "360p",
                "240p",
                "144p"
        );
        qualityCombo.getSelectionModel().select(QUALITY_BEST);
    }

    private static void fillQualityComboFromHeights(ComboBox<String> qualityCombo, java.util.Set<Integer> heights) {
        if (qualityCombo == null) return;

        // If we couldn't detect anything, keep a SAFE fallback list (no 4K/2K).
        if (heights == null || heights.isEmpty()) {
            fillQualityCombo(qualityCombo);
            return;
        }

        java.util.List<Integer> sorted = new java.util.ArrayList<>(heights);
        sorted.removeIf(h -> h == null || h <= 0);
        sorted.sort(java.util.Comparator.reverseOrder());

        qualityCombo.getItems().clear();
        qualityCombo.getItems().add(QUALITY_BEST);
        qualityCombo.getItems().add(QUALITY_SEPARATOR);

        for (Integer h : sorted) {
            qualityCombo.getItems().add(formatHeightLabel(h));
        }

        qualityCombo.getSelectionModel().select(QUALITY_BEST);
    }

    private static String formatHeightLabel(int h) {
        // keep your labels consistent
        if (h >= 2160) return "2160p (4K)";
        if (h >= 1440) return "1440p (2K)";
        if (h >= 540 && h < 720) return "540p";
        return h + "p";
    }

    // Normalize slightly-off heights from yt-dlp (e.g., 1434 -> 1440, 1076 -> 1080, 718 -> 720)
    private static int normalizeHeight(int h) {
        if (h <= 0) return -1;

        // Ignore tiny storyboard/thumbnail heights
        if (h < 120) return -1;

        // Accept common ladder heights (and close variants)
        int[] ladder = {144, 240, 360, 480, 540, 720, 1080, 1440, 2160, 4320};
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;

        for (int v : ladder) {
            int diff = Math.abs(h - v);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = v;
            }
        }

        // Tolerance: allow small encoder variations
        int tolerance = 28;
        return (bestDiff <= tolerance) ? best : -1;
    }

    private static Set<Integer> normalizeHeights(Set<Integer> in) {
        Set<Integer> out = new TreeSet<>();
        if (in == null) return out;
        for (Integer v : in) {
            if (v == null) continue;
            int nh = normalizeHeight(v);
            if (nh > 0) out.add(nh);
        }
        return out;
    }


    // ================== Last download folder (persisted) ==================
    private static final String PREF_KEY_LAST_FOLDER = "gx_last_download_folder";

    private String getLastDownloadFolderOrDefault() {
        try {
            java.util.prefs.Preferences prefs =
                    java.util.prefs.Preferences.userNodeForPackage(MainController.class);

            String v = prefs.get(PREF_KEY_LAST_FOLDER, null);
            if (v != null) {
                v = v.trim();
                if (!v.isEmpty()) {
                    java.nio.file.Path p = java.nio.file.Path.of(v).toAbsolutePath().normalize();
                    return p.toString();
                }
            }
        } catch (Exception ignored) {}

        // Default
        try {
            return java.nio.file.Path.of(System.getProperty("user.home"), "Downloads").toString();
        } catch (Exception ignored) {
            return System.getProperty("user.home");
        }
    }

    private void saveLastDownloadFolder(String folder) {
        if (folder == null) return;
        String v = folder.trim();
        if (v.isEmpty()) return;

        try {
            java.util.prefs.Preferences prefs =
                    java.util.prefs.Preferences.userNodeForPackage(MainController.class);

            prefs.put(PREF_KEY_LAST_FOLDER, v);
            try { prefs.flush(); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\t", " ")
                .replace("\n", " ")
                .replace("\r", " ");
    }
    private static String unesc(String s) {
        if (s == null) return "";
        return s.replace("\\\\", "\\");
    }

    private void warmMissingThumbnailsAsync(java.util.List<DownloadRow> rows) {
        if (rows == null || rows.isEmpty()) return;

        // Delay a bit so UI shows instantly first.
        UI_DELAY_EXEC.schedule(() -> {
            new Thread(() -> {
                for (DownloadRow r : rows) {
                    try {
                        if (r == null) continue;
                        if (r.url == null || r.url.isBlank()) continue;

                        // If already has a thumb, skip
                        String cur = null;
                        try { cur = (r.thumbUrl == null) ? null : r.thumbUrl.get(); } catch (Exception ignored) {}
                        if (cur != null && !cur.isBlank()) continue;

                        // If cached file exists, use it
                        java.nio.file.Path cached = com.grabx.app.grabx.thumbs.ThumbnailCacheManager.getCachedPath(r.url);
                        if (cached != null) {
                            java.nio.file.Path fCached = cached;
                            javafx.application.Platform.runLater(() -> {
                                try { r.thumbUrl.set(fCached.toUri().toString()); } catch (Exception ignored) {}
                            });
                            continue;
                        }

                        // Otherwise: compute thumb URL (YouTube only) and cache it to disk
                        String thumbUrl = thumbFromUrl(r.url);
                        if (thumbUrl == null || thumbUrl.isBlank()) continue;

                        // blocking fetch in this background thread (NOT UI)
                        com.grabx.app.grabx.thumbs.ThumbnailCacheManager.fetchAndCacheBlocking(r.url, thumbUrl);

                        java.nio.file.Path after = com.grabx.app.grabx.thumbs.ThumbnailCacheManager.getCachedPath(r.url);
                        if (after != null) {
                            java.nio.file.Path fAfter = after;
                            javafx.application.Platform.runLater(() -> {
                                try { r.thumbUrl.set(fAfter.toUri().toString()); } catch (Exception ignored) {}
                            });
                        }

                    } catch (Exception ignored) {}
                }
            }, "grabx-warm-thumbs").start();
        }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // Cache for computed sizes to avoid re-running yt-dlp repeatedly (key: url|mode|quality)
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> SIZE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Limit expensive yt-dlp probes (avoid spawning many processes at once)
    private static final int VIDEO_SIZE_THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService VIDEO_SIZE_EXEC = new ThreadPoolExecutor(
            VIDEO_SIZE_THREADS,
            VIDEO_SIZE_THREADS,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            r -> {
                Thread t = new Thread(r, "video-size-probe");
                t.setDaemon(true);
                return t;
            },
            // If queue is full, don't block UI threads
            new ThreadPoolExecutor.AbortPolicy()
    );

    // Avoid duplicate probes for the same (url|quality) while one is already running
    private static final java.util.Set<String> VIDEO_SIZE_INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();


    private void showAddLinkDialog(String prefillUrl) {
        if (addLinkDialogService == null) {
            if (statusText != null) statusText.setText("Add link service is not ready");
            return;
        }

        addLinkDialogService.show(prefillUrl);
    }

    private void closeActiveAddLinkDialogIfOpen() {
        try {
            if (activeAddLinkDialog != null) {
                activeAddLinkDialog.close();
            }
        } catch (Exception ignored) {}

        addLinkDialogOpen = false;
        activeAddLinkUrlField = null;
        activeAddLinkDialog = null;
    }

    private DownloadRow createDownloadRow(String url, String mode, String quality, String title) {
        ensureDownloadsListView();

        String u = (url == null) ? "" : url.trim();
        u = normalizeYoutubeSingleVideoUrl(u);

        String folder = getLastDownloadFolderOrDefault();

        String t = (title == null || title.isBlank()) ? shorten(u) : title;
        if (t == null || t.isBlank()) t = "New item";

        String m = (mode == null || mode.isBlank()) ? MODE_VIDEO : mode;
        String q = (quality == null || quality.isBlank())
                ? (MODE_AUDIO.equals(m) ? AUDIO_DEFAULT_FORMAT : QUALITY_BEST)
                : quality;

        DownloadRow r = new DownloadRow(u, t,downloadOrderSeq.getAndIncrement(), folder, m, q);
        try { r.status.set("Preparing"); } catch (Exception ignored) {}
        return r;
    }

    private Long probeContentLength(String url) {
        if (url == null || url.isBlank()) return null;
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url.trim()).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(6500);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "GrabX/1.0");
            conn.connect();
            long len = conn.getContentLengthLong();
            return len > 0 ? len : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void updateMissingSidebarItem() {
        Platform.runLater(() -> {
            boolean hasMissing = false;
            try {
                for (DownloadRow r : downloadItems) {
                    if (r == null) continue;
                    if (r.state != null && r.state.get() == DownloadRow.State.MISSING) {
                        hasMissing = true;
                        break;
                    }
                }
            } catch (Exception ignored) {}

            int idx = -1;
            try {
                for (int i = 0; i < sidebarList.getItems().size(); i++) {
                    SidebarItem si = sidebarList.getItems().get(i);
                    if (si != null && "MISSING".equalsIgnoreCase(si.key)) { // <-- غيّر key إذا اسمها مختلف عندك
                        idx = i;
                        break;
                    }
                }
            } catch (Exception ignored) {}

            if (hasMissing) {
                if (idx < 0) {
                    sidebarList.getItems().add(SIDEBAR_MISSING_ITEM);
                }
            } else {
                if (idx >= 0) {
                    SidebarItem selected = null;
                    try { selected = sidebarList.getSelectionModel().getSelectedItem(); } catch (Exception ignored) {}
                    boolean selectedIsMissing = selected != null && "MISSING".equalsIgnoreCase(selected.key);

                    sidebarList.getItems().remove(idx);

                    if (selectedIsMissing) {
                        try { sidebarList.getSelectionModel().selectFirst(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private Long fetchSizeWithYtDlp(String url, String mode, String quality) {
        if (url == null || url.isBlank()) return null;

        final String u = url.trim();

        try {
            java.nio.file.Path yt = com.grabx.app.grabx.util.YtDlpManager.ensureAvailable();
            if (yt == null) return null;

            boolean audioOnly = MODE_AUDIO.equals(mode)
                    || "Audio".equalsIgnoreCase(mode)
                    || "Audio only".equalsIgnoreCase(mode);

            // Build EXACT selector (same as download)
            String selector;
            if (audioOnly) {
                selector = "bestaudio/best";
            } else {
                String q = (quality == null) ? QUALITY_BEST : quality;
                if (q == null || q.isBlank() || QUALITY_SEPARATOR.equals(q) || QUALITY_BEST.equals(q)) {
                    selector = "bv*+ba/best";
                } else {
                    int h = parseHeightFromLabel(q);
                    if (h > 0) selector = "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/best";
                    else selector = "bv*+ba/best";
                }
            }
            String key = u + "||" + selector;
            Long cached = SIZE_CACHE.get(key);
            if (cached != null && cached > 0) return cached;
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(yt.toAbsolutePath().toString());
            cmd.add("-J");
            cmd.add("--no-playlist");
            cmd.add("--skip-download");
            cmd.add("--no-warnings");
            cmd.add("-f");
            cmd.add(selector);
            cmd.add(u);

            Path ffmpeg = com.grabx.app.grabx.util.FfmpegManager.ensureAvailable();
            if (ffmpeg != null) {
                cmd.add("--ffmpeg-location");
                cmd.add(ffmpeg.toAbsolutePath().toString());
                System.out.println("[FFMPEG] Using ffmpeg at: " + ffmpeg);
            } else {
                System.out.println("[FFMPEG] ffmpeg not available, yt-dlp will try system ffmpeg.");
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

            Process p = pb.start();

            StringBuilder sb = new StringBuilder(256 * 1024);
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();

            String json = sb.toString();
            if (json.isBlank()) return null;

            long duration = extractLongFieldFast(json, "duration");

            long total = 0L;

            int rf = json.indexOf("\"requested_formats\"");
            if (rf >= 0) {
                int arrStart = json.indexOf('[', rf);
                if (arrStart > 0) {
                    int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
                    if (arrEnd > arrStart) {
                        String arr = json.substring(arrStart, arrEnd + 1);
                        java.util.regex.Matcher objM = java.util.regex.Pattern
                                .compile("\\{[^\\{\\}]*\\}")
                                .matcher(arr);

                        while (objM.find()) {
                            String obj = objM.group();
                            long part = extractLongFieldFast(obj, "filesize");
                            if (part <= 0) part = extractLongFieldFast(obj, "filesize_approx");
                            if (part <= 0 && duration > 0) {
                                long tbr = extractLongFieldFast(obj, "tbr"); // Kbps
                                if (tbr > 0) part = (long) ((tbr * 1000.0 / 8.0) * duration);
                            }
                            if (part > 0) total += part;
                        }
                    }
                }
            }

            // fallback: single format
            if (total <= 0) {
                long fs = extractLongFieldFast(json, "filesize");
                if (fs <= 0) fs = extractLongFieldFast(json, "filesize_approx");
                if (fs > 0) total = fs;
            }

            if (total > 0) {
                SIZE_CACHE.put(key, total);
                return total;
            }

            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long extractLongFieldFast(String jsonChunk, String field) {
        if (jsonChunk == null || jsonChunk.isBlank() || field == null || field.isBlank()) return 0L;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\\"" + java.util.regex.Pattern.quote(field) + "\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                    .matcher(jsonChunk);
            if (!m.find()) return 0L;
            String v = m.group(1);
            if (v == null || v.isBlank()) return 0L;
            if (v.contains(".")) return (long) Double.parseDouble(v);
            return Long.parseLong(v);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static int findMatchingBracket(String s, int start, char open, char close) {
        if (s == null || start < 0 || start >= s.length()) return -1;
        int depth = 0;
        boolean inStr = false;
        char prev = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && prev != '\\') inStr = !inStr;
            if (!inStr) {
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            prev = c;
        }
        return -1;
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


    private void ensureDownloadsListView() {
        if (downloadsList == null) return;

        downloadsList.setItems(downloadService.view());

        DownloadRowActions rowActions = new DownloadRowActions(
                downloadStateCoordinator,
                activeProcesses,
                stopReasons,
                downloadItems,
                statusText,
                (row, isResume) -> startDownloadRow(row, isResume),
                this::updateMissingSidebarItem,
                () -> {
                    try {
                        downloadService.setCombinedFilter(
                                currentSidebarFilterKey,
                                (searchField == null ? "" : searchField.getText())
                        );
                    } catch (Exception ignored) {}
                },
                this::revealInFileManager
        );

        downloadsList.setCellFactory(lv -> new DownloadRowCell(
                downloadStateCoordinator::pause,
                downloadStateCoordinator::resume,
                downloadStateCoordinator::cancel,
                rowActions::openDownloadLink,
                rowActions::openFolderForDownloadRow,
                rowActions::retryDownloadRow,
                rowActions::clearDownloadRow,
                MainController::setupSvgButton,
                this::installTooltip,
                ICON_PAUSE,
                ICON_PLAY,
                ICON_CANCEL,
                ICON_LINK,
                ICON_FOLDER_OPEN,
                ICON_RETRY,
                ICON_CLEAR
        ));

        // Make it look nicer without selection highlight
        downloadsList.setSelectionModel(new NoSelectionModel<>());
    }

    private String fetchTitleWithOEmbed(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String u = url.trim();

            String oembed = "https://www.youtube.com/oembed?format=json&url=" +
                    java.net.URLEncoder.encode(u, java.nio.charset.StandardCharsets.UTF_8);

            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(oembed).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "GrabX/1.0");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;

            String json;
            try (var in = conn.getInputStream()) {
                json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            if (json == null || json.isBlank()) return null;

            // Extract: "title":"..."
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\\"title\\\"\\s*:\\s*\\\"(.*?)\\\"", java.util.regex.Pattern.DOTALL)
                    .matcher(json);

            if (!m.find()) return null;

            String title = m.group(1);
            if (title == null) return null;

            // Minimal JSON unescape
            title = title.replace("\\\\\"", "\"")
                    .replace("\\\\n", " ")
                    .replace("\\\\r", " ")
                    .replace("\\\\t", " ")
                    .replace("\\\\/", "/")
                    .replace("\\\\\\\\", "\\");

            title = unescapeUnicode(title);
            title = title.trim();
            return title.isBlank() ? null : title;

        } catch (Exception ignored) {
            return null;
        }
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
        ensureDownloadsListView();
        url = normalizeYoutubeSingleVideoUrl(url);
        String initialTitle = shorten(url);
        if (initialTitle == null || initialTitle.isBlank()) initialTitle = "New item";

        DownloadRow row = new DownloadRow(url, initialTitle,downloadOrderSeq.getAndIncrement(),folder, mode, quality);
        if (historyService != null) historyService.attachAutoSave(row);
        // خَلّي “Loading/Preparing” في status مش في العنوان
        row.status.set("Preparing");

        // Thumbnail
        String thumbUrl = null;
        try {
            thumbUrl = thumbFromUrl(url);
            if (thumbUrl != null && !thumbUrl.isBlank()) {

                // show instantly (cached if exists, else remote)
                try {
                    java.nio.file.Path cached =
                            com.grabx.app.grabx.thumbs.ThumbnailCacheManager.getCachedPath(url);

                    if (cached != null) {
                        row.thumbUrl.set(cached.toUri().toString());   // file://...
                    } else {
                        row.thumbUrl.set(thumbUrl);                   // https://...
                    }
                } catch (Exception ignored) {
                    try { row.thumbUrl.set(thumbUrl); } catch (Exception ignored2) {}
                }

                // ensure it gets cached on disk once
                String finalUrl = url;
                com.grabx.app.grabx.thumbs.ThumbnailCacheManager.fetchAndCacheAsync(
                        url,
                        thumbUrl,
                        () -> {
                            java.nio.file.Path p =
                                    com.grabx.app.grabx.thumbs.ThumbnailCacheManager.getCachedPath(finalUrl);
                            if (p != null) {
                                javafx.application.Platform.runLater(() -> {
                                    try { row.thumbUrl.set(p.toUri().toString()); } catch (Exception ignored) {}
                                });
                            }
                        }
                );
            }
        } catch (Exception ignored) {}

        // ✅ أي تغيير مهم = احفظ التاريخ (العنوان/الحالة/مسار الملف)


        Platform.runLater(() -> {
            downloadItems.add(0, row);
            if (historyService != null) historyService.scheduleSave();

            startDownloadRow(row, false);
        });

        if (statusText != null) statusText.setText("Queued: " + row.title.get());

        // ✅ oEmbed title (سريع) وبعدها احفظ التاريخ مرة ثانية
        if (url != null && !url.isBlank()) {
            String finalUrl1 = url;
            new Thread(() -> {
                String realTitle = fetchTitleWithOEmbed(finalUrl1);
                Platform.runLater(() -> {
                    if (realTitle != null && !realTitle.isBlank()) {
//                        row.setTitleOnce(realTitle);
                        row.setTitleOnce(makeUniqueUiTitle(realTitle, row));
                        if (statusText != null) statusText.setText("Queued: " + realTitle);
                    } else {
                        String fallback = shorten(finalUrl1);
                        if (fallback == null || fallback.isBlank()) fallback = "Unknown title";
//                        row.setTitleOnce(fallback);
                        row.setTitleOnce(makeUniqueUiTitle(fallback, row));
                        if (statusText != null) statusText.setText("Queued: " + fallback);
                    }
                });
            }, "title-oembed").start();
        }
    }

    private static String safeGet(javafx.beans.property.StringProperty p) {
        try {
            if (p == null) return "";
            String v = p.get();
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    // --- tiny JSON helpers (no external libs) ---
    private static String j(String s) {
        if (s == null) return "null";
        String v = s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + v + "\"";
    }

    // Only keep the version with yt-dlp --progress-template and regex patterns DEST1, DEST2, MERGE, PROG, etc.

    private void startDownloadRow(DownloadRow row, boolean resume) {
        downloadRunner.start(row, resume);
    }

    public static boolean supportsAudioThumbnailEmbedding(String fmt) {
        if (fmt == null) return false;
        String f = fmt.trim().toLowerCase(java.util.Locale.ROOT);

        // wav intentionally NOT included
        return f.equals("mp3")
                || f.equals("m4a")
                || f.equals("opus")
                || f.equals("ogg")
                || f.equals("flac")
                || f.equals("mka")
                || f.equals("mkv")
                || f.equals("mp4")
                || f.equals("m4b")
                || f.equals("m4p");
    }

    public static String probeOutputFilename(java.nio.file.Path yt,
                                             String url,
                                             String selector,
                                             java.nio.file.Path outDir,
                                             String outTpl) {
        if (yt == null || url == null || url.isBlank() || selector == null || outDir == null || outTpl == null) return null;

        try {
            java.util.List<String> probe = new java.util.ArrayList<>();
            probe.add(yt.toAbsolutePath().toString());
            probe.add("--no-warnings");
            probe.add("--no-playlist");
            probe.add("--skip-download");
            probe.add("--encoding"); probe.add("utf-8");

            // keep the same anti-403 args as the real download
            probe.add("--user-agent");
            probe.add("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
            probe.add("--referer");
            probe.add("https://www.youtube.com/");
            probe.add("--extractor-args");
            probe.add("youtube:player_client=android");

            probe.add("-f");
            probe.add(selector);

            probe.add("-o");
            probe.add(outDir.resolve(outTpl).toString());

            // Print the final filename chosen by yt-dlp
            probe.add("--print");
            probe.add("filename");

            probe.add(url.trim());

            Process p = new ProcessBuilder(probe)
                    .redirectErrorStream(true)
                    .start();

            String line;
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                line = br.readLine();
            }
            try { p.waitFor(); } catch (Exception ignored) {}

            if (line == null) return null;
            line = line.trim();
            return line.isBlank() ? null : line;

        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeYoutubeSingleVideoUrl(String input) {
        if (input == null) return null;
        String u = input.trim();
        if (u.isBlank()) return u;

        try {
            java.net.URI uri = new java.net.URI(u);

            String host = uri.getHost();
            if (host == null) host = "";
            host = host.toLowerCase(java.util.Locale.ROOT);

            // handle youtu.be/<id>
            if (host.contains("youtu.be")) {
                String path = uri.getPath(); // "/ID"
                if (path != null && path.length() > 1) {
                    String id = path.substring(1);
                    int slash = id.indexOf('/');
                    if (slash > 0) id = id.substring(0, slash);
                    if (!id.isBlank()) {
                        return "https://www.youtube.com/watch?v=" + id;
                    }
                }
                return u;
            }

            // only normalize youtube domains
            if (!host.contains("youtube.com")) return u;

            String path = uri.getPath() == null ? "" : uri.getPath();

            // shorts/<id> -> watch?v=<id>
            if (path.startsWith("/shorts/")) {
                String id = path.substring("/shorts/".length());
                int slash = id.indexOf('/');
                if (slash > 0) id = id.substring(0, slash);
                if (!id.isBlank()) {
                    return "https://www.youtube.com/watch?v=" + id;
                }
                return u;
            }

            // watch?v=<id>  -> keep only v
            String q = uri.getRawQuery();
            if (q == null || q.isBlank()) return u;

            String v = null;
            for (String part : q.split("&")) {
                int eq = part.indexOf('=');
                String k = (eq >= 0) ? part.substring(0, eq) : part;
                String val = (eq >= 0) ? part.substring(eq + 1) : "";
                if ("v".equals(k)) {
                    v = java.net.URLDecoder.decode(val, java.nio.charset.StandardCharsets.UTF_8);
                    break;
                }
            }
            if (v == null || v.isBlank()) return u;

            return "https://www.youtube.com/watch?v=" + v;

        } catch (Exception ignored) {
            return u;
        }
    }

    private String makeUniqueUiTitle(String base, DownloadRow self) {
        if (base == null) base = "";
        base = base.trim();
        if (base.isEmpty()) return base;

        int max = 0;

        for (DownloadRow r : downloadItems) {
            if (r == null || r == self) continue;

            String t = null;
            try { t = r.title.get(); } catch (Exception ignored) {}
            if (t == null) continue;
            t = t.trim();

            if (t.equals(base)) {
                max = Math.max(max, 1);
                continue;
            }

            // match: "base (N)"
            if (t.startsWith(base + " (") && t.endsWith(")")) {
                String inside = t.substring((base + " (").length(), t.length() - 1).trim();
                try {
                    int n = Integer.parseInt(inside);
                    max = Math.max(max, n + 1);
                } catch (Exception ignored) {}
            }
        }

        return (max == 0) ? base : (base + " (" + max + ")");
    }

    private static boolean isAudioExtension(String ext) {
        if (ext == null) return false;
        ext = ext.toLowerCase(java.util.Locale.ROOT).trim();
        return ext.equals("m4a") || ext.equals("mp3") || ext.equals("aac") || ext.equals("opus") ||
                ext.equals("ogg") || ext.equals("flac") || ext.equals("wav");
    }

    public static boolean isAudioStreamFromDestinationLine(String s) {
        if (s == null) return false;

        // ExtractAudio lines are always audio
        if (s.startsWith("[ExtractAudio]")) return true;

        int idx = s.indexOf("Destination");
        if (idx < 0) return false;

        int colon = s.indexOf(":", idx);
        String path = (colon >= 0)
                ? s.substring(colon + 1).trim()
                : s.substring(idx + "Destination".length()).trim();

        if (path.isEmpty()) return false;

        // remove quotes if any
        if ((path.startsWith("\"") && path.endsWith("\"")) || (path.startsWith("'") && path.endsWith("'"))) {
            path = path.substring(1, path.length() - 1).trim();
        }
        if (path.isEmpty()) return false;
        try {
            java.util.regex.Matcher mid = java.util.regex.Pattern
                    .compile("\\.f(\\d{2,4})\\.", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(path);

            if (mid.find()) {
                int fid = Integer.parseInt(mid.group(1));

                // Common audio-only ids on YouTube
                if (fid == 139 || fid == 140 || fid == 141 || fid == 249 || fid == 250 || fid == 251
                        || fid == 599 || fid == 600) {
                    return true;
                }
                // if it has a format id and it's not in audio set -> very likely video
                return false;
            }
        } catch (Exception ignored) {}

        // Fallback: audio extensions => audio
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return false;

        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT).trim();
        return isAudioExtension(ext);
    }

    public static void killProcessTree(Process p) {
        if (p == null) return;

        try {
            ProcessHandle h = p.toHandle();

            // graceful destroy descendants first
            h.descendants().forEach(ph -> {
                try { ph.destroy(); } catch (Exception ignored) {}
            });
            try { h.destroy(); } catch (Exception ignored) {}

            // small wait then force kill still-alive
            try { Thread.sleep(150); } catch (Exception ignored) {}

            h.descendants().forEach(ph -> {
                try { if (ph.isAlive()) ph.destroyForcibly(); } catch (Exception ignored) {}
            });
            try { if (h.isAlive()) h.destroyForcibly(); } catch (Exception ignored) {}

        } catch (Exception ignored) {
            // fallback: at least kill main process
            try { p.destroyForcibly(); } catch (Exception ignored2) {}
        }
    }

    // Decode Unicode escape sequences like \u0645\u0627 -> ما
    private static String unescapeUnicode(String s) {
        if (s == null || !s.contains("\\u")) return s;

        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                try {
                    String hex = s.substring(i + 2, i + 6);
                    int code = Integer.parseInt(hex, 16);
                    out.append((char) code);
                    i += 5;
                } catch (Exception e) {
                    out.append(c);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static long parseLongSafe(String s) {
        try {
            if (s == null) return 0L;
            s = s.trim();
            if (s.isEmpty() || "NA".equalsIgnoreCase(s) || "None".equalsIgnoreCase(s)) return 0L;
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    // Decimal units (KB/MB/GB) to avoid MiB/GiB and reduce visual clutter
    public static String formatBytesDecimal(long bytes) {
        if (bytes <= 0) return "0 B";
        double b = (double) bytes;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (b >= 1000.0 && i < u.length - 1) {
            b /= 1000.0;
            i++;
        }
        return String.format(java.util.Locale.US, "%.1f %s", b, u[i]);
    }

    public static String normalizeSpeedUnit(String spd) {
        if (spd == null) return null;

        String s = spd.trim();
        if (s.isEmpty() || "NA".equalsIgnoreCase(s)) return "";

        // توحيد الوحدات
        s = s.replace("KiB/s", "KB/s")
                .replace("MiB/s", "MB/s")
                .replace("GiB/s", "GB/s")
                .replace("TiB/s", "TB/s");

        // استخراج الرقم + الوحدة
        java.util.regex.Matcher m =
                java.util.regex.Pattern
                        .compile("([0-9]+(?:\\.[0-9]+)?)\\s*(KB/s|MB/s|GB/s|TB/s)",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(s);

        if (!m.find()) return s;

        double value;
        try {
            value = Double.parseDouble(m.group(1));
        } catch (Exception e) {
            return s;
        }

        String unit = m.group(2).toUpperCase();

        return String.format(java.util.Locale.US, "%.1f %s", value, unit);
    }

    // --- Thumbnail helpers and cache ---
    public static final java.util.Map<String, javafx.scene.image.Image> MAIN_THUMB_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String extractYoutubeId(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;

        try {
            // youtu.be/<id>
            int yb = u.indexOf("youtu.be/");
            if (yb >= 0) {
                String s = u.substring(yb + "youtu.be/".length());
                int q = s.indexOf('?');
                if (q >= 0) s = s.substring(0, q);
                int a = s.indexOf('&');
                if (a >= 0) s = s.substring(0, a);
                s = s.trim();
                return s.isEmpty() ? null : s;
            }


            int sh = u.indexOf("/shorts/");
            if (sh >= 0) {
                String s = u.substring(sh + "/shorts/".length());
                int q = s.indexOf('?');
                if (q >= 0) s = s.substring(0, q);
                int a = s.indexOf('&');
                if (a >= 0) s = s.substring(0, a);
                s = s.trim();
                return s.isEmpty() ? null : s;
            }

            // embed/<id>
            int em = u.indexOf("/embed/");
            if (em >= 0) {
                String s = u.substring(em + "/embed/".length());
                int q = s.indexOf('?');
                if (q >= 0) s = s.substring(0, q);
                int a = s.indexOf('&');
                if (a >= 0) s = s.substring(0, a);
                s = s.trim();
                return s.isEmpty() ? null : s;
            }

            // watch?v=<id>
            int v = u.indexOf("v=");
            if (v >= 0) {
                String s = u.substring(v + 2);
                int a = s.indexOf('&');
                if (a >= 0) s = s.substring(0, a);
                int h = s.indexOf('#');
                if (h >= 0) s = s.substring(0, h);
                s = s.trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Exception ignored) {}

        return null;
    }

    public String thumbFromUrl(String url) {
        String id = extractYoutubeId(url);
        if (id == null || id.isBlank()) return null;
        return "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";
    }


    // Reveal/select a file in the OS file manager (best-effort)
    private void revealInFileManager(java.nio.file.Path file) {
        if (file == null) return;

        try {
            java.nio.file.Path f = file.toAbsolutePath().normalize();

            // ✅ If file is missing: DO NOT open folder. Show notice instead.
            if (!java.nio.file.Files.exists(f)) {
                javafx.application.Platform.runLater(() -> {
                    try {
                        javafx.scene.control.Alert a =
                                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
                        a.setTitle("File not found");
                        a.setHeaderText("This file is no longer in its original location.");
                        a.setContentText("It looks like the file was moved, renamed, or deleted.");
                        a.show();
                    } catch (Exception ignored) {}
                });
                return;
            }

            String os = System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT);

            if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", f.toString()).start();
                return;
            }

            if (os.contains("win")) {
                new ProcessBuilder("explorer", "/select,", f.toString()).start();
                return;
            }

            // Linux: best effort (no universal select)
            java.nio.file.Path parent = f.getParent();
            if (parent != null) openInFileManager(parent);

        } catch (Exception ignored) {}
    }


    // === Thumbnail rendering helpers (cover crop + rounded clip) ===
    public static void applyCoverViewport(ImageView iv, Image img, double targetW, double targetH) {
        if (iv == null || img == null) return;

        double iw = img.getWidth();
        double ih = img.getHeight();
        if (iw <= 0 || ih <= 0 || targetW <= 0 || targetH <= 0) {
            iv.setViewport(null);
            return;
        }

        double targetRatio = targetW / targetH;
        double imgRatio = iw / ih;

        double cropW, cropH;

        if (imgRatio > targetRatio) {
            // wider than target -> crop left/right
            cropH = ih;
            cropW = ih * targetRatio;
        } else {
            // taller than target -> crop top/bottom
            cropW = iw;
            cropH = iw / targetRatio;
        }

        double x = Math.max(0, (iw - cropW) / 2.0);
        double y = Math.max(0, (ih - cropH) / 2.0);

        iv.setViewport(new javafx.geometry.Rectangle2D(x, y, cropW, cropH));
    }

    public static void applyRoundedClip(Region region, double arc) {
        if (region == null) return;

        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(arc * 2);
        clip.setArcHeight(arc * 2);
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        region.setClip(clip);
    }

    // --- Reconcile persisted state with actual files on disk (fixes: completed restored as PAUSED) ---
    private void reconcileLoadedRowsWithDisk() {
        Platform.runLater(() -> {
            try {
                if (downloadItems == null) return;
                for (DownloadRow r : downloadItems) {
                    if (r == null) continue;
                    reconcileOneRowWithDisk(r);
                }
            } catch (Exception ignored) {}

            // refresh view + sidebar
            try { downloadService.refilter();} catch (Exception ignored) {}
            try { updateMissingSidebarItem(); } catch (Exception ignored) {}
        });
    }

    private void reconcileOneRowWithDisk(DownloadRow r) {
        if (r == null) return;

        // 1) If output file exists -> must be COMPLETED
        try {
            java.nio.file.Path out = (r.outputFile != null) ? r.outputFile.get() : null;
            if (out != null) {
                java.nio.file.Path abs = out.toAbsolutePath().normalize();
                if (java.nio.file.Files.exists(abs)) {
                    r.setState(DownloadRow.State.COMPLETED);
                    try { r.progress.set(1.0); } catch (Exception ignored) {}
                    try { r.speed.set(""); } catch (Exception ignored) {}
                    try { r.eta.set(""); } catch (Exception ignored) {}

                    // fill size if empty
                    try {
                        if (r.size != null) {
                            String cur = r.size.get();
                            if (cur == null || cur.isBlank()) {
                                long bytes = java.nio.file.Files.size(abs);
                                r.size.set(formatBytesDecimal(bytes));
                            }
                        }
                    } catch (Exception ignored) {}

                    return;
                }
            }
        } catch (Exception ignored) {}

        // 2) If history says COMPLETED but file missing -> MISSING
        try {
            if (r.state != null && r.state.get() == DownloadRow.State.COMPLETED) {
                r.setState(DownloadRow.State.MISSING);
            }
        } catch (Exception ignored) {}

        // 3) If app restarts and row was DOWNLOADING but no process is running -> revert to QUEUED
        // (prevents stuck states after restart)
        try {
            DownloadRow.State st = (r.state != null) ? r.state.get() : null;
            if (st == DownloadRow.State.DOWNLOADING) {
                r.setState(DownloadRow.State.QUEUED);
                try { r.progress.set(0); } catch (Exception ignored) {}
                try { r.speed.set(""); } catch (Exception ignored) {}
                try { r.eta.set(""); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }


    // ========= Playlist Screen (v1 - lightweight) =========
    private void openPlaylistWindow(String playlistUrl, String folder) {
        final String playlistFolder = (folder == null || folder.isBlank())
                ? getLastDownloadFolderOrDefault()
                : folder;

        Stage stage = new Stage();
        stage.setTitle("Playlist");

        Button calcSize = new Button("Compute size");
        calcSize.getStyleClass().addAll("gx-btn", "gx-btn-ghost");
        calcSize.setDisable(true);

        // Sequential probing (top -> bottom)
        final java.util.Set<String> qualitiesInflight = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger probeIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean probingNow = new java.util.concurrent.atomic.AtomicBoolean(false);

        final double PLAYLIST_Q_COMBO_W = 160;
        final int PLAYLIST_MAX_SELECTED = 200; // hard cap to avoid UI/native crashes on huge playlists

        // ✅ NEW: Playlist should stay lightweight (NO size probing here)
        final boolean SHOW_PLAYLIST_SIZES = false;

        ObservableList<PlaylistEntry> items = FXCollections.observableArrayList();

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
            if (root != null && root.getScene() != null && root.getScene().getWindow() != null) {
                stage.initOwner(root.getScene().getWindow());
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
                setText(empty ? null : item);

                boolean disabled = QUALITY_SEPARATOR.equals(item) || QUALITY_CUSTOM.equals(item);
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
        StringProperty globalDesiredQuality = new SimpleStringProperty(QUALITY_BEST);

        globalModeCombo.getSelectionModel().select(MODE_VIDEO);
        globalQualityCombo.getSelectionModel().select(QUALITY_BEST);
        globalDesiredMode.set(MODE_VIDEO);
        globalDesiredQuality.set(QUALITY_BEST);

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
                calcSize.setDisable(!any);
            } catch (Exception ignored) {}
        };

        list.setItems(items);
        list.setPrefHeight(420);
        list.setFocusTraversable(false);
        list.setSelectionModel(new NoSelectionModel<>());

        // Throttle refreshes
        PauseTransition refreshThrottle = new PauseTransition(Duration.millis(140));
        Runnable requestRefresh = () -> {
            refreshThrottle.stop();
            refreshThrottle.setOnFinished(ev -> list.refresh());
            refreshThrottle.playFromStart();
        };

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

        final java.util.concurrent.atomic.AtomicBoolean userQualityInteracted =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        final javafx.beans.property.BooleanProperty updatingSelection =
                new javafx.beans.property.SimpleBooleanProperty(false);

        final Runnable updateGlobalMixedState = () -> {
            if (anyQualityPopupOpen.get() || updatingGlobalCombo.get()) {
                pendingMixedUpdate.set(true);
                return;
            }
            if (!userQualityInteracted.get()) return;

            boolean anyManual = false;
            boolean anySelected = false;

            for (PlaylistEntry it : items) {
                if (it == null) continue;
                if (!it.isSelected()) continue;
                if (it.isUnavailable()) continue;
                anySelected = true;
                if (it.isManualQuality()) {
                    anyManual = true;
                    break;
                }
            }
            if (!anySelected) return;

            String desired = globalDesiredQuality.get();
            if (desired == null || desired.isBlank()) desired = QUALITY_BEST;

            updatingGlobalCombo.set(true);
            try {
                if (anyManual) {
                    if (!globalQualityCombo.getItems().contains(QUALITY_CUSTOM)) {
                        globalQualityCombo.getItems().add(0, QUALITY_CUSTOM);
                    }
                    globalQualityCombo.getSelectionModel().select(QUALITY_CUSTOM);
                } else {
                    if (globalQualityCombo.getItems().contains(QUALITY_CUSTOM)) {
                        globalQualityCombo.getItems().remove(QUALITY_CUSTOM);
                    }
                    if (globalQualityCombo.getItems().contains(desired)) {
                        globalQualityCombo.getSelectionModel().select(desired);
                    } else {
                        String mapped = pickClosestSupportedQuality(desired, new java.util.ArrayList<>(globalQualityCombo.getItems()));
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
                        java.util.Set<Integer> normalized = normalizeHeights(new java.util.HashSet<>(globalHeightsUnion));
                        java.util.List<Integer> sorted = new java.util.ArrayList<>(normalized);
                        sorted.sort(java.util.Comparator.reverseOrder());

                        globalQualityCombo.getItems().add(QUALITY_BEST);
                        globalQualityCombo.getItems().add(QUALITY_SEPARATOR);
                        for (Integer h : sorted) globalQualityCombo.getItems().add(formatHeightLabel(h));
                    }

                    if (QUALITY_CUSTOM.equals(prev) && keepCustom) {
                        globalQualityCombo.getSelectionModel().select(QUALITY_CUSTOM);
                    } else if (globalQualityCombo.getItems().contains(prev)) {
                        globalQualityCombo.getSelectionModel().select(prev);
                    } else {
                        String mapped = pickClosestSupportedQuality(prev, new java.util.ArrayList<>(globalQualityCombo.getItems()));
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
                    globalQualityCombo.getSelectionModel().select(QUALITY_BEST);
                    globalDesiredQuality.set(QUALITY_BEST);
                }
            } finally {
                updatingGlobalCombo.set(false);
            }

            // Apply to ALL items (no size probing)
            for (PlaylistEntry it : items) {
                if (it == null || it.isUnavailable()) continue;
                it.setManualQuality(false);
                it.setQuality(MODE_AUDIO.equals(val) ? globalDesiredQuality.get() : QUALITY_BEST);
            }

            requestRefreshSafe.run();
            Platform.runLater(updateGlobalMixedState);
            userQualityInteracted.set(true);
        });

        Runnable startNextProbe = new Runnable() {
            @Override
            public void run() {
                if (probingNow.getAndSet(true)) return; // already running

                // find next item to probe
                int i = probeIndex.get();
                while (i < items.size()) {
                    PlaylistEntry it = items.get(i);
                    i++;

                    if (it == null || it.isUnavailable()) continue;
                    if (it.isQualitiesLoaded()) continue; // already ready

                    // ✅ Probe only selected rows
                    if (!it.isSelected()) continue;

                    String vid = it.getId();
                    if (vid == null || vid.isBlank()) continue;

                    // de-dupe inflight
                    if (!qualitiesInflight.add(vid)) continue;

                    probeIndex.set(i); // next position for later

                    String url = youtubeWatchUrl(vid);

                    boolean queued = probeVideoQualitiesAsync(url, vid, pr -> {
                        try {
                            java.util.Set<Integer> heights = (pr == null) ? java.util.Set.of() : pr.heights;
                            java.util.Set<Integer> norm = normalizeHeights(heights);

                            if (norm != null && !norm.isEmpty()) {
                                globalHeightsUnion.addAll(norm);
                            }
                            Platform.runLater(updateGlobalQualityCombo);

                            java.util.ArrayList<String> labels = new java.util.ArrayList<>();
                            labels.add(QUALITY_BEST);
                            labels.add(QUALITY_SEPARATOR);

                            java.util.List<Integer> sorted = (norm == null)
                                    ? new java.util.ArrayList<>()
                                    : new java.util.ArrayList<>(norm);
                            sorted.sort(java.util.Comparator.reverseOrder());
                            for (Integer h : sorted) labels.add(formatHeightLabel(h));

                            it.setAvailableQualities(labels);

                            // don't compute sizes here; keep empty map
                            if (it.getSizeByQuality() == null) it.setSizeByQuality(new java.util.HashMap<>());

                            // apply desired (video)
                            if (!MODE_AUDIO.equals(globalDesiredMode.get())) {
                                String desired = it.getQuality();
                                if (!it.isManualQuality()) {
                                    desired = globalDesiredQuality.get();
                                    if (desired == null || desired.isBlank()) desired = QUALITY_BEST;
                                }
                                String supported = pickClosestSupportedQuality(desired, it.getAvailableQualities());
                                it.setQuality(supported);
                            }

                            // ✅ READY now
                            it.setQualitiesLoaded(true);

                        } catch (Exception ignored) {
                            it.setQualitiesLoaded(false); // allow retry
                        } finally {
                            qualitiesInflight.remove(vid);
                            Platform.runLater(() -> {
                                requestRefreshSafe.run();
                                probingNow.set(false);
                                // continue with next item
                                this.run();
                            });
                        }
                    });

                    if (!queued) {
                        qualitiesInflight.remove(vid);
                        probingNow.set(false);
                        // try again later / move on
                        Platform.runLater(this);
                    }
                    return;
                }

                // done
                probingNow.set(false);
            }
        };

        // Probe qualities sequentially (NO size) - kick the queue only
        java.util.function.Consumer<PlaylistEntry> ensureProbed = (PlaylistEntry it) -> {
            if (it == null) return;
            if (it.isUnavailable()) return;
            // لا تغيّر flags هنا — startNextProbe هو المسؤول الوحيد
            Platform.runLater(startNextProbe);
        };



        list.setCellFactory(lv -> new ListCell<>() {

            private final CheckBox cb = new CheckBox();
            private final Label title = new Label();
            private final javafx.scene.control.Tooltip titleTip = new javafx.scene.control.Tooltip();
            private final PauseTransition titleTipThrottle = new PauseTransition(Duration.millis(45));
            private String lastTitleForTip = null;
            private final Label meta = new Label();

            private final VBox textBox = new VBox(4);

            private final StackPane thumbBox = new StackPane();
            private final ImageView thumb = new ImageView();
            private final Label placeholder = new Label("NO PREVIEW");

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

            {
                setStyle("-fx-background-color: transparent;");

                cb.getStyleClass().addAll("gx-check", "gx-playlist-check");
                cb.setFocusTraversable(false);

                thumb.setFitWidth(96);
                thumb.setFitHeight(54);
                thumb.setPreserveRatio(true);
                thumb.setSmooth(true);

                placeholder.getStyleClass().add("gx-playlist-thumb-placeholder");

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
                title.hoverProperty().addListener((o, was, isNow) -> anyTitleHoverHold.set(isNow));

                // Do NOT install tooltip here; we install/uninstall per-row only when truncated (see updateItem)

                meta.getStyleClass().addAll("gx-task-meta", "gx-playlist-meta");

                textBox.getChildren().addAll(title, meta);
                HBox.setHgrow(textBox, Priority.ALWAYS);
                textBox.setMinWidth(0);
                textBox.setMaxWidth(Double.MAX_VALUE);
                // Allow the VBox to shrink (so the row doesn't force horizontal expansion)
                textBox.setPrefWidth(0);

                qualityCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality", "gx-playlist-item-combo");
                qualityCombo.setPrefWidth(PLAYLIST_Q_COMBO_W);
                qualityCombo.setMinWidth(PLAYLIST_Q_COMBO_W);
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

                qualityCombo.showingProperty().addListener((obs, was, isNow) -> anyQualityPopupOpen.set(isNow));

                qualityCombo.valueProperty().addListener((obs, old, val) -> {
                    if (updatingRowCombo || suppressQualityListener) return;
                    if (val == null) return;
                    if (QUALITY_SEPARATOR.equals(val) || QUALITY_CUSTOM.equals(val)) return;

                    PlaylistEntry it = getItem();
                    if (it == null || it.isUnavailable()) return;

                    userQualityInteracted.set(true);

                    it.setQuality(val);

                    String modeNow = globalDesiredMode.get();
                    if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

                    // Playlist screen: no size probing (sizes appear in main downloads list)
                    String desired = globalDesiredQuality.get();
                    if (desired == null || desired.isBlank()) desired = QUALITY_BEST;

                    String globalMapped;
                    if (MODE_AUDIO.equals(modeNow)) {
                        globalMapped = desired;
                    } else {
                        java.util.List<String> avail = it.getAvailableQualities();
                        globalMapped = (avail == null || avail.isEmpty())
                                ? desired
                                : pickClosestSupportedQuality(desired, avail);
                    }

                    boolean manual = !val.equals(globalMapped);
                    it.setManualQuality(manual);

                    meta.setText(buildMetaLine(it)); // (will no longer show sizes because we never fill size map)
                    Platform.runLater(updateGlobalMixedState);
                });

                card.getStyleClass().add("gx-playlist-card");
                card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                card.getChildren().addAll(cb, thumbBox, textBox, qualityCombo);
                // Force each cell/card to fit the ListView viewport width (prevents horizontal growth)
                setMaxWidth(Double.MAX_VALUE);
                prefWidthProperty().bind(lv.widthProperty().subtract(20));
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
                    PlaylistEntry it = getItem();
                    if (it == null) return;

                    if (it.isUnavailable()) {
                        cb.setSelected(false);
                        it.setSelected(false);
                        return;
                    }

                    if (isNow && !updatingSelection.get()) {
                        int cur = selectedCount.getAsInt();
                        if (cur >= PLAYLIST_MAX_SELECTED) {
                            cb.setSelected(false);
                            it.setSelected(false);
                            syncCardSelectedStyle(it, card);
                            try { if (status != null) status.setText("Selection limit: " + PLAYLIST_MAX_SELECTED + " items"); } catch (Exception ignored) {}
                            return;
                        }
                    }

                    it.setSelected(isNow);
                    syncCardSelectedStyle(it, card);

                    if (isNow && !updatingSelection.get()) {
                        // شغّل التحليل التسلسلي من فوق لتحت
                        Platform.runLater(startNextProbe);
                    }

                    if (!updatingSelection.get()) {
                        userQualityInteracted.set(true);
                        Platform.runLater(updateGlobalMixedState);
                    }

                    try { refreshAddState.run(); } catch (Exception ignored) {}
                });
            }

            @Override
            protected void updateItem(PlaylistEntry item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    // avoid stale tooltip on reused cells
                    try { title.setTooltip(null); } catch (Exception ignored) {}
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

                meta.setText(buildMetaLine(item));

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
                    placeholder.setText("UNAVAILABLE");
                    placeholder.setVisible(true);
                    thumb.setImage(null);
                    meta.setText("Unavailable");
                    card.setOpacity(0.55);
                    setGraphic(card);
                    return;
                } else {
                    card.setOpacity(1.0);
                }

                // ===== THUMBNAIL =====
                placeholder.setText("NO PREVIEW");

                String tid = item.getId();
                Image cachedThumb = (tid == null) ? null : PLAYLIST_THUMB_CACHE.get(tid);

                if (cachedThumb != null) {
                    thumb.setImage(cachedThumb);
                    placeholder.setVisible(false);

                } else if (item.getThumbUrl() != null && !item.getThumbUrl().isBlank()) {
                    placeholder.setVisible(true);

                    if (tid != null && PLAYLIST_THUMB_INFLIGHT.add(tid)) {
                        Image img = new Image(item.getThumbUrl(), true);
                        PLAYLIST_THUMB_CACHE.put(tid, img);

                        img.progressProperty().addListener((o, oldP, newP) -> {
                            if (newP != null && newP.doubleValue() >= 1.0) {
                                PLAYLIST_THUMB_INFLIGHT.remove(tid);
                                Platform.runLater(() -> {
                                    PlaylistEntry now = getItem();
                                    if (now != null && tid.equals(now.getId()) && img.getException() == null) {
                                        placeholder.setVisible(false);
                                    }
                                });
                            }
                        });

                        img.exceptionProperty().addListener((o, oldEx, ex) ->
                                PLAYLIST_THUMB_INFLIGHT.remove(tid)
                        );
                    }

                    thumb.setImage(PLAYLIST_THUMB_CACHE.get(tid));
                }

                // ===== MODE =====
                String modeNow = globalDesiredMode.get();
                if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

                // ===== AUDIO MODE =====
                if (MODE_AUDIO.equals(modeNow)) {
                    updatingRowCombo = true;
                    try {
                        qualityCombo.getItems().setAll(buildAudioOptions());
                        qualityCombo.setDisable(false);

                        String cur = item.getQuality();
                        if (cur == null || cur.isBlank() || QUALITY_BEST.equals(cur)) {
                            cur = globalDesiredQuality.get();
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

                    if (item.isSelected()) Platform.runLater(startNextProbe);

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
                            cur = pickClosestSupportedQuality(cur, q);
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
        });

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

        final java.util.concurrent.atomic.AtomicBoolean didDownload =
                new java.util.concurrent.atomic.AtomicBoolean(false);

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
                getClass().getResource("/com/grabx/app/grabx/styles/sidebar.css").toExternalForm()
        );

        rootBox.applyCss();
        rootBox.layout();
        stage.setScene(scene);

        globalQualityCombo.valueProperty().addListener((obs, old, val) -> {
            if (val == null) return;
            if (QUALITY_SEPARATOR.equals(val)) return;
            if (QUALITY_CUSTOM.equals(val)) return;
            if (updatingGlobalCombo.get()) return;

            userQualityInteracted.set(true);
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
                            : pickClosestSupportedQuality(val, avail);
                    it.setQuality(mapped);
                    // ✅ NO size probing
                }
            }

            requestRefreshSafe.run();
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
                }
            } finally {
                updatingSelection.set(false);
            }
            requestRefreshSafe.run();
            refreshAddState.run();
        });
        calcSize.setOnAction(e -> {
            String modeNow = globalDesiredMode.get();
            if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

            // AUDIO: ما بنحسب حجم هون (خليه يظهر بالواجهة الرئيسية عند بدء التحميل)
            if (MODE_AUDIO.equals(modeNow)) {
                try { if (status != null) status.setText("Size is not calculated in Audio mode."); } catch (Exception ignored) {}
                return;
            }

            int scheduled = 0;
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

                ensurePlaylistSizeAsync(it, qNow, requestRefreshSafe);
                scheduled++;
            }

            try {
                if (status != null) {
                    status.setText(scheduled > 0
                            ? ("Computing sizes for " + scheduled + " selected item(s)...")
                            : "No items selected.");
                }
            } catch (Exception ignored) {}
        });

        cancel.setOnAction(e -> {
            stage.close();

            if (!didDownload.get() && reopenAddLinkAfterPlaylist) {
                final String u = reopenAddLinkPrefillUrl;

                reopenAddLinkAfterPlaylist = false;
                reopenAddLinkPrefillUrl = null;

                javafx.application.Platform.runLater(() -> openOrUpdateAddLinkDialog(u));
            }
        });

        download.setOnAction(e -> {
            saveLastDownloadFolder(playlistFolder);

            didDownload.set(true);
            try {
                if (activeAddLinkDialog != null) activeAddLinkDialog.hide();
            } catch (Exception ignored) {}

            java.util.List<PlaylistEntry> batch = items.stream()
                    .filter(it -> it != null && it.isSelected() && !it.isUnavailable())
                    .sorted(java.util.Comparator.comparingInt(PlaylistEntry::getIndex))
                    .toList();

            if (batch.isEmpty()) {
                if (statusText != null) statusText.setText("No items selected.");
                return;
            }

            String modeNow = globalDesiredMode.get();
            String desiredNow = globalDesiredQuality.get();

            if (playlistBatchService != null) {
                // ✅ لازم يكون عندك في PlaylistBatchService دالة اسمها enqueue(...)
                playlistBatchService.enqueue(batch, modeNow, desiredNow);
            } else {
                // fallback مؤقت لحد ما نضمن السيرفس
                enqueuePlaylistBatch(batch, modeNow, desiredNow);
            }


            if (statusText != null) {
                statusText.setText("Queued playlist: " + batch.size() + " items");
            }

            reopenAddLinkAfterPlaylist = false;
            reopenAddLinkPrefillUrl = null;

            stage.close();
        });

        // Load playlist entries asynchronously
        new Thread(() -> {
            java.util.List<PlaylistEntry> loaded;
            try {
                // Phase 1 move: flat playlist probing is owned by PlaylistService
                loaded = new com.grabx.app.grabx.core.service.PlaylistService()
                        .loadFlatPlaylist(playlistUrl);
            } catch (Exception ignored) {
                loaded = java.util.List.of();
            }
            List<PlaylistEntry> finalLoaded = loaded;
            Platform.runLater(() -> {
                items.setAll(finalLoaded);
                if (finalLoaded.isEmpty()) {
                    status.setText("Could not load playlist (yt-dlp missing?)");
                } else {
                    long bad = finalLoaded.stream().filter(PlaylistEntry::isUnavailable).count();
                    status.setText("Loaded " + finalLoaded.size() + " items" + (bad > 0 ? (" • " + bad + " unavailable") : ""));
                }

                updatingSelection.set(true);
                try {
                    for (PlaylistEntry it : items) {
                        if (it == null) continue;
                        it.setSelected(false);
                    }
                } finally {
                    updatingSelection.set(false);
                }

                updatingGlobalCombo.set(true);
                try {
                    globalQualityCombo.getSelectionModel().select(QUALITY_BEST);
                } finally {
                    updatingGlobalCombo.set(false);
                }
                globalDesiredQuality.set(QUALITY_BEST);

                requestRefreshSafe.run();
                refreshAddState.run();
                probeIndex.set(0);

            });
        }, "probe-playlist").start();

        stage.showAndWait();
    }

    // Show tooltip only when title is truncated (for playlist rows)
    private static boolean isLabelTextTruncated(Label label) {
        if (label == null) return false;
        try {
            // If the label is not laid out yet, we can't decide.
            double avail = label.getWidth();
            if (avail <= 1) return false;

            javafx.geometry.Insets in = label.getInsets();
            if (in != null) {
                avail -= (in.getLeft() + in.getRight());
            }
            // Also subtract a tiny tolerance to avoid flicker.
            avail -= 2.0;
            if (avail <= 1) return false;

            // Measure text width using a Text node with the same font.
            String s = label.getText();
            if (s == null) s = "";
            // Ignore the LTR mark we add for numbering alignment.
            s = s.replace("\u200E", "");

            javafx.scene.text.Text t = new javafx.scene.text.Text(s);
            t.setFont(label.getFont());
            double textW = t.getLayoutBounds().getWidth();

            return textW > avail;
        } catch (Exception ex) {
            return false;
        }
    }

    // De-dupe playlist size probes (key: videoId||quality)
    private final java.util.Set<String> PLAYLIST_SIZE_INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Playlist: compute size ON DEMAND (button) to keep UI fast.
// VIDEO only. Uses shared SIZE_CACHE + VIDEO_SIZE_EXEC if available.
    private void ensurePlaylistSizeAsync(PlaylistEntry it, String qLabel, Runnable requestRefreshSafe) {
        if (it == null || it.isUnavailable()) return;
        if (qLabel == null || qLabel.isBlank()) return;
        if (QUALITY_SEPARATOR.equals(qLabel) || QUALITY_CUSTOM.equals(qLabel)) return;

        String vid = it.getId();
        if (vid == null || vid.isBlank()) return;

        String videoUrl = youtubeWatchUrl(vid);
        if (videoUrl == null || videoUrl.isBlank()) return;

        // 0) Global size cache hit
        try {
            String cacheKey = videoUrl + "|" + MODE_VIDEO + "|" + qLabel;
            Long cachedBytes = SIZE_CACHE.get(cacheKey);
            if (cachedBytes != null && cachedBytes > 0) {
                var cur = it.getSizeByQuality();
                String exist = (cur == null) ? null : cur.get(qLabel);
                if (exist == null || exist.isBlank()) {
                    var next = new java.util.HashMap<String, String>();
                    if (cur != null) next.putAll(cur);
                    next.put(qLabel, formatBytesDecimal(cachedBytes));
                    it.setSizeByQuality(next);
                    if (requestRefreshSafe != null) requestRefreshSafe.run();
                }
                return;
            }
        } catch (Exception ignored) {}

        // 1) Already present in the item map
        try {
            var cur = it.getSizeByQuality();
            if (cur != null) {
                String exist = cur.get(qLabel);
                if (exist != null && !exist.isBlank()) return;
            }
        } catch (Exception ignored) {}

        // 2) De-dupe inflight
        String inflightKey = vid + "||" + qLabel;
        if (!PLAYLIST_SIZE_INFLIGHT.add(inflightKey)) return;

        Runnable job = () -> {
            Long bytes = null;
            try {
                String selector;
                if (QUALITY_BEST.equals(qLabel)) {
                    selector = "bv*+ba/b";
                } else {
                    int h = parseHeightFromLabel(qLabel);
                    selector = (h > 0) ? buildFormatSelectorForHeight(h) : "bv*+ba/b";
                }

                bytes = fetchCombinedSizeBytesWithYtDlpPrint(videoUrl, selector);

                if (bytes != null && bytes > 0) {
                    try {
                        String cacheKey = videoUrl + "|" + MODE_VIDEO + "|" + qLabel;
                        SIZE_CACHE.put(cacheKey, bytes);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
            } finally {
                PLAYLIST_SIZE_INFLIGHT.remove(inflightKey);
            }

            Long fbytes = bytes;
            Platform.runLater(() -> {
                try {
                    if (fbytes != null && fbytes > 0) {
                        var next = new java.util.HashMap<String, String>();
                        var cur = it.getSizeByQuality();
                        if (cur != null) next.putAll(cur);
                        next.put(qLabel, formatBytesDecimal(fbytes));
                        it.setSizeByQuality(next);
                        if (requestRefreshSafe != null) requestRefreshSafe.run();
                    }
                } catch (Exception ignored) {}
            });
        };

        try {
            VIDEO_SIZE_EXEC.execute(job);
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            PLAYLIST_SIZE_INFLIGHT.remove(inflightKey);
        } catch (Exception ex) {
            PLAYLIST_SIZE_INFLIGHT.remove(inflightKey);
            new Thread(job, "playlist-size-probe").start();
        }
    }

    private static final String CARD_SELECTED_CLASS = "gx-selected";

    private void syncCardSelectedStyle(PlaylistEntry it, javafx.scene.Node cardNode) {
        if (cardNode == null) return;

        boolean sel = it != null && it.isSelected() && !it.isUnavailable();

        if (sel) {
            if (!cardNode.getStyleClass().contains(CARD_SELECTED_CLASS)) {
                cardNode.getStyleClass().add(CARD_SELECTED_CLASS);
            }
        } else {
            cardNode.getStyleClass().remove(CARD_SELECTED_CLASS);
        }
    }
    private void enqueuePlaylistBatch(java.util.List<PlaylistEntry> batch, String batchMode, String batchDefaultQuality) {
        try {
            if (playlistBatchService != null) {
                playlistBatchService.enqueue(batch, batchMode, batchDefaultQuality);
                return;
            }
        } catch (Exception ignored) {}

        // fallback only if service wiring failed for any reason
        String modeNow = (batchMode == null || batchMode.isBlank()) ? MODE_VIDEO : batchMode;

        playlistBatchMode = modeNow;
        playlistBatchDefaultQuality =
                (batchDefaultQuality == null || batchDefaultQuality.isBlank())
                        ? (MODE_AUDIO.equals(modeNow) ? AUDIO_DEFAULT_FORMAT : QUALITY_BEST)
                        : batchDefaultQuality;

        for (PlaylistEntry it : batch) {
            playlistDownloadQueue.addLast(it);
        }

        for (PlaylistEntry it : batch) {
            String url = youtubeWatchUrl(it.getId());
            String q = it.getQuality();
            if (q == null || q.isBlank()) q = playlistBatchDefaultQuality;

            DownloadRow pendingRow = addPendingRowToMainList(url, modeNow, q, it.displayTitle());
            playlistRowByVideoId.put(it.getId(), pendingRow);
        }

        if (playlistBatchRunning.compareAndSet(false, true)) {
            startNextPlaylistDownload();
        }
    }

    private void startNextPlaylistDownload() {
        // moved to PlaylistBatchService
    }

    private void startSingleDownloadFromPlaylist(String url,
                                                 String mode,
                                                 String quality,
                                                 String title,
                                                 DownloadRow existingRow,
                                                 Runnable onDone) {
        // moved to PlaylistBatchService
    }

    private DownloadRow startDownloadForUrl(String url, String mode, String quality, String title) {
        DownloadRow r = createDownloadRow(url, mode, quality, title);
        applyThumbForRow(r, url);
        Platform.runLater(() -> {
            downloadItems.add(0, r);
            if (historyService != null) historyService.scheduleSave();

            startDownloadRow(r, false);   // ✅ محرك التحميل الحقيقي عندك

        });

        return r;
    }

    private DownloadRow addPendingRowToMainList(String url, String mode, String quality, String title) {
        DownloadRow r = createDownloadRow(url, mode, quality, title);
        applyThumbForRow(r, url);
        r.setState(DownloadRow.State.PENDING);

        Platform.runLater(() -> {
            downloadItems.add(0, r);
            if (historyService != null) historyService.scheduleSave();
        });

        return r;
    }

    // Reuse across single downloads + playlist pending rows
    private void applyThumbForRow(DownloadRow row, String url) {
        if (row == null) return;
        if (url == null || url.isBlank()) return;

        try {
            String thumbUrl = thumbFromUrl(url);
            if (thumbUrl == null || thumbUrl.isBlank()) return;

            // show instantly (cached if exists, else remote)
            try {
                java.nio.file.Path cached =
                        com.grabx.app.grabx.thumbs.ThumbnailCacheManager.getCachedPath(url);

                if (cached != null) {
                    row.thumbUrl.set(cached.toUri().toString());   // file://...
                } else {
                    row.thumbUrl.set(thumbUrl);                   // https://...
                }
            } catch (Exception ignored) {
                try { row.thumbUrl.set(thumbUrl); } catch (Exception ignored2) {}
            }

            // ensure it gets cached on disk once
            String finalUrl = url;
            com.grabx.app.grabx.thumbs.ThumbnailCacheManager.fetchAndCacheAsync(
                    url,
                    thumbUrl,
                    () -> {
                        java.nio.file.Path p =
                                com.grabx.app.grabx.thumbs.ThumbnailCacheManager.getCachedPath(finalUrl);
                        if (p != null) {
                            javafx.application.Platform.runLater(() -> {
                                try { row.thumbUrl.set(p.toUri().toString()); } catch (Exception ignored) {}
                            });
                        }
                    }
            );
        } catch (Exception ignored) {}
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



    private static String youtubeThumbUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }


    private static String shorten(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 46 ? s.substring(0, 43) + "..." : s;
    }

    // ========= Clipboard auto-paste (v1) =========
    private String lastClipboardText = "";


    // ================== Safe deferred open for Add Link dialog ==================
    private final java.util.concurrent.atomic.AtomicBoolean addLinkOpenScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);


    /**
     * Opens Add Link safely OR updates it if already open.
     */
    private void openOrUpdateAddLinkDialog(String prefillUrl) {
        String url = (prefillUrl != null && isHttpUrl(prefillUrl)) ? prefillUrl.trim() : null;
        if (url != null) pendingAddLinkPrefillUrl = url;

        // If already open -> update field immediately
        if (addLinkDialogOpen) {
            if (url != null) {
                if (activeAddLinkUrlField != null) {
                    activeAddLinkUrlField.setText(url);
                    activeAddLinkUrlField.positionCaret(activeAddLinkUrlField.getText().length());
                    Platform.runLater(activeAddLinkUrlField::requestFocus);
                } else {
                    // dialog is opening but field not ready yet
                    pendingAddLinkPrefillUrl = url;
                }
            }
            return;
        }

        // prevent rapid duplicate opens
        if (!addLinkOpenScheduled.compareAndSet(false, true)) return;

        final String captured = (url != null) ? url : null;

        Platform.runLater(() -> {
            try {
                // tiny delay avoids "show during layout/animation"
                PauseTransition pt = new PauseTransition(Duration.millis(80));
                pt.setOnFinished(e -> {
                    try {
                        showAddLinkDialog(captured);
                    } finally {
                        addLinkOpenScheduled.set(false);
                    }
                });
                pt.playFromStart();
            } catch (Exception ex) {
                addLinkOpenScheduled.set(false);
            }
        });
    }

    // Helper: read clipboard text, never throws
    private String readClipboardTextSafe() {
        try {
            javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
            if (cb != null && cb.hasString()) {
                return cb.getString().trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // Helper: is this string an HTTP/HTTPS URL
    private boolean isHttpUrl(String s) {
        if (s == null) return false;
        String ss = s.trim().toLowerCase();
        return ss.startsWith("http://") || ss.startsWith("https://");
    }


    // Cache probe results (per URL) so Add Link can switch qualities instantly
    private static final long VIDEO_INFO_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    private static final java.util.concurrent.ConcurrentHashMap<String, ProbeQualitiesResult> VIDEO_INFO_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class ProbeQualitiesResult {
        final java.util.Set<Integer> heights;              // normalized heights
        final java.util.Map<Integer, Long> bytesByHeight;  // normalized height -> total bytes (video+audio)
        final java.util.Map<Integer, String> sizeByHeight; // normalized height -> "~xx MB" text
        final long bestBytes;                               // best (highest height) bytes
        final long createdAtMs;

        ProbeQualitiesResult(java.util.Set<Integer> heights,
                             java.util.Map<Integer, Long> bytesByHeight,
                             java.util.Map<Integer, String> sizeByHeight,
                             long bestBytes,
                             long createdAtMs) {
            this.heights = (heights == null) ? java.util.Set.of() : heights;
            this.bytesByHeight = (bytesByHeight == null) ? java.util.Map.of() : bytesByHeight;
            this.sizeByHeight = (sizeByHeight == null) ? java.util.Map.of() : sizeByHeight;
            this.bestBytes = bestBytes;
            this.createdAtMs = createdAtMs;
        }

        boolean isFresh() {
            return (System.currentTimeMillis() - createdAtMs) <= VIDEO_INFO_TTL_MS;
        }
    }

    // Open a folder in the OS file manager
    private static void openInFileManager(java.nio.file.Path folder) {
        if (folder == null) return;
        try {
            java.nio.file.Path dir = folder.toAbsolutePath().normalize();
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir.toFile());
                return;
            }
        } catch (Exception ignored) {}

        // Fallbacks
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("mac")) {
                new ProcessBuilder("open", folder.toAbsolutePath().toString()).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("explorer", folder.toAbsolutePath().toString()).start();
            } else {
                new ProcessBuilder("xdg-open", folder.toAbsolutePath().toString()).start();
            }
        } catch (Exception ignored) {}
    }
}