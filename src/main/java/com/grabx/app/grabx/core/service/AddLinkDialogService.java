package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.util.YtDlpManager;
import com.grabx.app.grabx.util.VideoQualityUtils;
import com.grabx.app.grabx.core.model.probe.VideoProbeService;
import com.grabx.app.grabx.core.service.UrlAnalysisService.ContentType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ProgressIndicator;

public final class AddLinkDialogService {
    private static final int MAX_SIZE_CACHE_ENTRIES = 128;

    // ===================== API =====================
    private final AtomicLong sessionId = new AtomicLong();

    public interface Callbacks {
        // UI helpers
        void installClickToDefocus(DialogPane pane);
        void bringWindowToFront(javafx.stage.Window w);

        // App behavior
        String shorten(String s);

        // Folder prefs
        String getLastDownloadFolderOrDefault();
        void saveLastDownloadFolder(String folder);

        // Add item
        void addDownloadItemToList(String url, String folder, String mode, String quality);

        // Playlist flow: hide dialog + open playlist window + set any flags you want in MainController
        void onPlaylistDetected(String playlistUrl, String folder);

        // Status label write (optional)
        void setStatusText(String txt);
    }

private static javafx.scene.Node buildSuccessGraphic() {
    try {
        // Outer soft circle (slightly larger for better balance)
        Circle bg = new Circle(8);
        bg.setFill(Color.web("#8CC63F"));
        bg.setStroke(null);

        bg.setStrokeWidth(1.2);

        // Check mark (cleaner proportions)
        SVGPath check = new SVGPath();
        check.setContent("M6 10.5L9 13.5L15 7.5");
        check.setStroke(Color.web("#121826")); // same as dialog background
        check.setStrokeWidth(2.6);
        check.setFill(null);
        check.setSmooth(true);

        StackPane box = new StackPane(bg, check);
        box.setMinSize(20, 20);
        box.setPrefSize(20, 20);
        box.setMaxSize(20, 20);

        return box;

    } catch (Exception e) {
        Label l = new Label("✓");
        l.setTextFill(Color.web("#2ecc71"));
        l.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        return l;
    }
}

    public static final class Config {
        public final String MODE_VIDEO;
        public final String MODE_AUDIO;

        public final String QUALITY_BEST;
        public final String QUALITY_SEPARATOR;

        public final String AUDIO_DEFAULT_FORMAT;
        public final List<String> AUDIO_FORMATS;

        public final String THEME_BASE_CSS;
        public final String LAYOUT_CSS;
        public final String BUTTONS_CSS;
        public final String SIDEBAR_CSS;

        public Config(
                String MODE_VIDEO,
                String MODE_AUDIO,
                String QUALITY_BEST,
                String QUALITY_SEPARATOR,
                String AUDIO_DEFAULT_FORMAT,
                List<String> AUDIO_FORMATS,
                String THEME_BASE_CSS,
                String LAYOUT_CSS,
                String BUTTONS_CSS,
                String SIDEBAR_CSS
        ) {
            this.MODE_VIDEO = MODE_VIDEO;
            this.MODE_AUDIO = MODE_AUDIO;
            this.QUALITY_BEST = QUALITY_BEST;
            this.QUALITY_SEPARATOR = QUALITY_SEPARATOR;
            this.AUDIO_DEFAULT_FORMAT = AUDIO_DEFAULT_FORMAT;
            this.AUDIO_FORMATS = (AUDIO_FORMATS == null) ? List.of() : AUDIO_FORMATS;

            this.THEME_BASE_CSS = THEME_BASE_CSS;
            this.LAYOUT_CSS = LAYOUT_CSS;
            this.BUTTONS_CSS = BUTTONS_CSS;
            this.SIDEBAR_CSS = SIDEBAR_CSS;
        }
    }

    public AddLinkDialogService(Node root, ScheduledExecutorService uiDelayExec, Callbacks cb, Config cfg) {
        this.root = root;
        this.UI_DELAY_EXEC = uiDelayExec;
        this.cb = Objects.requireNonNull(cb, "callbacks");
        this.cfg = Objects.requireNonNull(cfg, "config");
    }

    /** call this from MainController */
    public void show(String prefillUrl) {
        showAddLinkDialog(prefillUrl);
    }

    public boolean isOpen() {
        return addLinkDialogOpen;
    }

    /** optional: close if open */
    public void closeIfOpen() {
        try {
            if (activeAddLinkDialog != null) activeAddLinkDialog.close();
        } catch (Exception ignored) {}
        addLinkDialogOpen = false;
        activeAddLinkUrlField = null;
        activeAddLinkDialog = null;
    }

    // ===================== internals =====================

    private final Node root;
    private final ScheduledExecutorService UI_DELAY_EXEC;
    private final Callbacks cb;
    private final Config cfg;
    private final VideoProbeCache videoProbeCache = new VideoProbeCache();
    private final VideoProbeService videoProbeService = new VideoProbeService();
    private final UrlAnalysisService urlAnalysisService = new UrlAnalysisService();

    private volatile boolean addLinkDialogOpen = false;
    private volatile TextField activeAddLinkUrlField;
    private volatile Dialog<ButtonType> activeAddLinkDialog;

    // cache for sizes
    private static final ConcurrentHashMap<String, Long> SIZE_CACHE = new ConcurrentHashMap<>();

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
            new ThreadPoolExecutor.AbortPolicy()
    );
    private static final Set<String> VIDEO_SIZE_INFLIGHT = ConcurrentHashMap.newKeySet();

    private void openAddLinkDialogDeferred(String prefillUrl) {
        final long mySession = sessionId.get();
        if (UI_DELAY_EXEC == null) {
            if (mySession != sessionId.get()) {
                return;
            }
            Platform.runLater(() -> showAddLinkDialog(prefillUrl));
            return;
        }
        UI_DELAY_EXEC.schedule(() -> Platform.runLater(() -> showAddLinkDialog(prefillUrl)),
                80, TimeUnit.MILLISECONDS);
    }

    private void showAddLinkDialog(String prefillUrl) {
        if (addLinkDialogOpen) {
            if (prefillUrl != null && urlAnalysisService.isHttpUrl(prefillUrl) && activeAddLinkUrlField != null) {
                activeAddLinkUrlField.setText(prefillUrl.trim());
                activeAddLinkUrlField.positionCaret(activeAddLinkUrlField.getText().length());
                Platform.runLater(activeAddLinkUrlField::requestFocus);
            }
            return;
        }

        final long mySession = sessionId.incrementAndGet();

        addLinkDialogOpen = true;
        activeAddLinkUrlField = null;

        Dialog<ButtonType> dialog = new Dialog<>();
        activeAddLinkDialog = dialog;
        dialog.setTitle("Add Link");
        dialog.setHeaderText(null);

        try {
            if (root != null && root.getScene() != null && root.getScene().getWindow() != null) {
                dialog.initOwner(root.getScene().getWindow());
                dialog.initModality(Modality.WINDOW_MODAL);
            }
        } catch (Exception ignored) {}

        DialogPane pane = dialog.getDialogPane();

        try { cb.installClickToDefocus(pane); } catch (Exception ignored) {}

        pane.getStyleClass().add("gx-dialog");
        pane.setStyle("-fx-background-color: #121826;");
        pane.setPadding(Insets.EMPTY);

        // load same css
        try {
            pane.getStylesheets().addAll(
                    getClass().getResource(cfg.THEME_BASE_CSS).toExternalForm(),
                    getClass().getResource(cfg.LAYOUT_CSS).toExternalForm(),
                    getClass().getResource(cfg.BUTTONS_CSS).toExternalForm(),
                    getClass().getResource(cfg.SIDEBAR_CSS).toExternalForm()
            );
        } catch (Exception ignored) {}

        dialog.setOnShowing(ev -> {
            Scene sc = pane.getScene();
            if (sc != null) {
                sc.setFill(Color.web("#121826"));
                sc.getRoot().setStyle("-fx-background-color: #121826;");
            }
            pane.applyCss();
            pane.layout();
        });

        ButtonType downLoadBtn = new ButtonType("Download", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, downLoadBtn);

        Button okBtn = (Button) pane.lookupButton(downLoadBtn);
        if (okBtn != null) okBtn.getStyleClass().addAll("gx-btn", "gx-btn-primary");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("gx-dialog-grid");
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(Insets.EMPTY);

        // URL
        TextField urlField = new TextField();
        urlField.setPromptText("Paste URL...");
        urlField.getStyleClass().add("gx-input");
        activeAddLinkUrlField = urlField;

        Button getBtn = new Button("Get");
        getBtn.getStyleClass().addAll("gx-btn", "gx-btn-ghost");
        getBtn.setMinWidth(130);
        getBtn.setPrefWidth(130);
        getBtn.setMaxWidth(130);

        // Mode + Quality
        ComboBox<String> modeCombo = new ComboBox<>();
        modeCombo.getItems().setAll(cfg.MODE_VIDEO, cfg.MODE_AUDIO);
        modeCombo.getSelectionModel().select(cfg.MODE_VIDEO);
        modeCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality");
        modeCombo.setMinWidth(420);
        modeCombo.setPrefWidth(420);
        modeCombo.setMaxWidth(420);

        ComboBox<String> qualityCombo = new ComboBox<>();
        qualityCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality");
        qualityCombo.setMinWidth(420);
        qualityCombo.setPrefWidth(420);
        qualityCombo.setMaxWidth(420);
        fillQualityCombo(qualityCombo);

        qualityCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setDisable(cfg.QUALITY_SEPARATOR.equals(item));
                setOpacity(cfg.QUALITY_SEPARATOR.equals(item) ? 0.55 : 1.0);
            }
        });
        qualityCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });

        final Set<Integer>[] lastProbedHeights = new Set[]{null};
        final Map<String, String>[] lastProbedSizeTextByQualityLabel = new Map[]{new HashMap<>()};

        // Folder
        TextField folderField = new TextField(cb.getLastDownloadFolderOrDefault());
        folderField.setEditable(false);
        folderField.getStyleClass().add("gx-input");

        Button browseBtn = new Button("Browse");
        browseBtn.getStyleClass().addAll("gx-btn", "gx-btn-ghost");
        browseBtn.setMinWidth(130);
        browseBtn.setPrefWidth(130);
        browseBtn.setMaxWidth(130);
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Download Folder");
            File selected = chooser.showDialog(pane.getScene().getWindow());
            if (selected != null) {
                folderField.setText(selected.getAbsolutePath());
                cb.saveLastDownloadFolder(selected.getAbsolutePath());
            }
        });

        Label info = new Label("Paste a link then click Get.");
        info.getStyleClass().add("gx-text-muted");
        info.setWrapText(true);
        info.setTextFill(Color.web("#9aa4b2"));

        info.setGraphicTextGap(8);

// ✅ Success icon (PNG) hidden by default
        info.setGraphicTextGap(8);

        final Node successGraphic = buildSuccessGraphic();

        Runnable showSuccess = () -> {
            try { info.setGraphic(successGraphic); } catch (Exception ignored) {}
        };

        Runnable hideSuccess = () -> {
            try { info.setGraphic(null); } catch (Exception ignored) {}
        };

// default hidden
        hideSuccess.run();
        // default: no icon
        info.setGraphic(null);

        info.setGraphicTextGap(8);
        info.setGraphic(null);

        Label sizeInfo = new Label("Estimated size: —");
        sizeInfo.getStyleClass().add("gx-text-muted");
        sizeInfo.setWrapText(true);
        sizeInfo.setTextFill(Color.web("#9aa4b2"));

        // size loading
        final long[] sizeReqId = {0};
        final boolean[] dialogAlive = { true };
        final int[] sizeDots = {0};

        javafx.animation.Timeline sizeLoadingTl = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(280), ev2 -> {
                    sizeDots[0] = (sizeDots[0] % 3) + 1;
                    String dots = switch (sizeDots[0]) {
                        case 1 -> ".";
                        case 2 -> ". .";
                        default -> ". . .";
                    };
                    sizeInfo.setText("Estimating: " + dots);
                })
        );
        sizeLoadingTl.setCycleCount(javafx.animation.Animation.INDEFINITE);

        Runnable stopSizeLoading = () -> {
            try { sizeLoadingTl.stop(); } catch (Exception ignored) {}
            sizeDots[0] = 0;
        };
        Consumer<String> setSizeText = (txt) -> {
            stopSizeLoading.run();
            boolean show = txt != null && !txt.isBlank();
            sizeInfo.setText(show ? txt : "");
            sizeInfo.setVisible(show);
            sizeInfo.setManaged(show);
        };
        Runnable startSizeLoading = () -> {
            stopSizeLoading.run();
            sizeInfo.setText("Estimating: .");
            sizeInfo.setVisible(true);
            sizeInfo.setManaged(true);
            try { sizeLoadingTl.playFromStart(); } catch (Exception ignored) {}
        };

        // Layout rows
        int rr = 0;
        grid.add(new Label("URL"), 0, rr);
        grid.add(urlField, 1, rr);
        grid.add(getBtn, 2, rr);
        GridPane.setHgrow(urlField, Priority.ALWAYS);

        rr++;
        Label modeLbl = new Label("Mode");
        grid.add(modeLbl, 0, rr);
        grid.add(modeCombo, 1, rr);
        GridPane.setHgrow(modeCombo, Priority.ALWAYS);

        rr++;
        Label qualityLbl = new Label("Quality");
        grid.add(qualityLbl, 0, rr);
        grid.add(qualityCombo, 1, rr);
        GridPane.setHgrow(qualityCombo, Priority.ALWAYS);

        rr++;
        grid.add(new Label("Folder"), 0, rr);
        grid.add(folderField, 1, rr);
        grid.add(browseBtn, 2, rr);
        GridPane.setHgrow(folderField, Priority.ALWAYS);

        rr++;
        grid.add(info, 1, rr, 2, 1);
        rr++;
        grid.add(sizeInfo, 1, rr, 2, 1);

        // default disabled
        modeCombo.setDisable(true);
        qualityCombo.setDisable(true);
        if (okBtn != null) okBtn.setDisable(true);

        final ContentType[] lastType = {ContentType.UNSUPPORTED};

        Runnable updateSizeAsync = () -> {
            if (!dialogAlive[0]) return;
            if (mySession != sessionId.get()) return;
            String u = urlField.getText() == null ? "" : urlField.getText().trim();
            if (u.isBlank()) { setSizeText.accept("Estimated size: —"); return; }

            String modeV = modeCombo.getValue() == null ? "" : modeCombo.getValue();
            String qV = qualityCombo.getValue() == null ? "" : qualityCombo.getValue();
            String key = u + "|" + modeV + "|" + qV;

            Long cached = SIZE_CACHE.get(key);
            if (cached != null && cached > 0) {
                setSizeText.accept("Estimated size: " + formatBytesDecimal(cached));
                return;
            }

            setSizeText.accept("Estimated size: — ");
            final long rid = ++sizeReqId[0];

            if (lastType[0] == ContentType.DIRECT_FILE) {
                startSizeLoading.run();
                new Thread(() -> {
                    Long bytes = probeContentLength(u);
                    if (bytes != null && bytes > 0) {
                        if (SIZE_CACHE.size() >= MAX_SIZE_CACHE_ENTRIES && !SIZE_CACHE.containsKey(key)) {
                            var iterator = SIZE_CACHE.keySet().iterator();
                            if (iterator.hasNext()) SIZE_CACHE.remove(iterator.next());
                        }
                        SIZE_CACHE.put(key, bytes);
                    }
                    Platform.runLater(() -> {
                        if (!dialogAlive[0]) return;
                        if (mySession != sessionId.get()) return;
                        if (rid != sizeReqId[0]) return;
                        if (bytes != null && bytes > 0) setSizeText.accept("Estimated size: " + formatBytesDecimal(bytes));
                        else setSizeText.accept("Estimated size: — ");
                    });
                }, "probe-size-head").start();
                return;
            }

            if (lastType[0] == ContentType.VIDEO) {
                stopSizeLoading.run();
                setSizeText.accept("");
                return;
            }

            setSizeText.accept("Estimated size: —");
        };

        modeCombo.valueProperty().addListener((obsM, oldM, newM) -> {
            if (newM == null) return;
            if (cfg.MODE_AUDIO.equals(newM)) {
                qualityCombo.getItems().setAll(buildAudioOptions());
                qualityCombo.getSelectionModel().select(cfg.AUDIO_DEFAULT_FORMAT);
            } else {
                fillQualityComboFromHeights(qualityCombo, lastProbedHeights[0]);
                qualityCombo.getSelectionModel().select(cfg.QUALITY_BEST);
            }
            if (okBtn != null && !okBtn.isDisabled() && lastType[0] == ContentType.VIDEO) {
                setSizeText.accept("");
            }
        });

        qualityCombo.valueProperty().addListener((obsQ, oldQ, newQ) -> {
            if (okBtn == null || okBtn.isDisabled()) return;
            if (lastType[0] != ContentType.VIDEO) return;
            if (newQ == null) return;
            if (cfg.QUALITY_SEPARATOR.equals(newQ)) return;
            setSizeText.accept("");
        });

        Runnable applyTypeToUi = () -> {
            ContentType t = lastType[0];

            if (t == ContentType.VIDEO) {
                modeCombo.setDisable(false);
                qualityCombo.setDisable(false);
                info.setText("Detected: Video. Choose mode/quality then Download.");
                info.setTextFill(Color.web("#9aa4b2"));
                showSuccess.run();
                if (okBtn != null) okBtn.setDisable(false);
                setGetButtonLoading(getBtn, false);
                setSizeText.accept("");

            } else if (t == ContentType.PLAYLIST) {
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Detected: Playlist. Opening Playlist screen...");
                info.setTextFill(Color.web("#9aa4b2"));
                info.setGraphic(null);
                if (okBtn != null) okBtn.setDisable(true);

            } else if (t == ContentType.DIRECT_FILE) {
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Detected: Direct link. Ready to Download.");
                info.setTextFill(Color.web("#9aa4b2"));
                showSuccess.run();
                if (okBtn != null) okBtn.setDisable(false);
                setGetButtonLoading(getBtn, false);
            } else {
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Unsupported or invalid URL.");
                info.setTextFill(Color.web("#ff4d4d"));
                info.setGraphic(null);
                if (okBtn != null) okBtn.setDisable(true);
            }
        };

        getBtn.setOnAction(e -> {
            setGetButtonLoading(getBtn, true);
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            if (url.isBlank()) {
                lastType[0] = ContentType.UNSUPPORTED;
                hideSuccess.run();
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                fillQualityCombo(qualityCombo);
                info.setText("Paste a link then click Get.");
                info.setTextFill(Color.web("#ff4d4d"));
                if (okBtn != null) okBtn.setDisable(true);
                setSizeText.accept("Estimated size: —");
                return;
            }

            lastType[0] = urlAnalysisService.analyze(url);

            if (lastType[0] == ContentType.VIDEO) {
                info.setText("Analyzing formats...");
                info.setTextFill(Color.web("#9aa4b2"));

                hideSuccess.run();

                final long probeSession = sessionId.get();
                final String requestedKey = videoProbeCache.cacheKey(url);
                videoProbeCache.get(url, () -> videoProbeService.probeHeights(url))
                        .whenComplete((cachedProbe, probeError) -> Platform.runLater(() -> {
                        if (!dialogAlive[0]) return;
                        if (probeSession != sessionId.get()) return;
                        String currentUrl = urlField.getText() == null ? "" : urlField.getText().trim();
                        if (!requestedKey.equals(videoProbeCache.cacheKey(currentUrl))) return;

                        Set<Integer> heights = probeError == null && cachedProbe != null
                                ? cachedProbe.heights()
                                : Set.of();
                        if (heights.isEmpty()) {
                            fillQualityCombo(qualityCombo);
                        } else {
                            fillQualityComboFromHeights(qualityCombo, heights);
                            lastProbedHeights[0] = heights;
                        }
                        applyTypeToUi.run();
                        if (okBtn != null) okBtn.setDisable(false);
                        setGetButtonLoading(getBtn, false);
                        }));

                return;
            }

            if (lastType[0] == ContentType.PLAYLIST) {
                cb.saveLastDownloadFolder(folderField.getText());
                try { if (activeAddLinkDialog != null) activeAddLinkDialog.hide(); } catch (Exception ignored) {}
                cb.onPlaylistDetected(url, folderField.getText());
                return;
            }

            applyTypeToUi.run();
            updateSizeAsync.run();
        });

        urlField.setOnAction(e -> getBtn.fire());

        urlField.textProperty().addListener((obs, oldV, newV) -> {
            lastType[0] = ContentType.UNSUPPORTED;
            lastProbedSizeTextByQualityLabel[0].clear();

            // ✅ اخفِ علامة النجاح فورًا عند تعديل الرابط
            hideSuccess.run();

            if (okBtn != null) okBtn.setDisable(true);
            modeCombo.setDisable(true);
            qualityCombo.setDisable(true);

            fillQualityCombo(qualityCombo);

            info.setText("Paste a link then click Get.");
            info.setTextFill(Color.web("#9aa4b2"));
            setSizeText.accept("Estimated size: — ");
        });

        pane.setContent(grid);
        pane.setMinWidth(760);
        pane.setPrefWidth(760);
        pane.setMaxWidth(760);
        dialog.setResizable(false);

        if (prefillUrl != null && !prefillUrl.isBlank()) urlField.setText(prefillUrl.trim());

        dialog.setOnShown(ev -> Platform.runLater(() -> {
            try { cb.bringWindowToFront(pane.getScene() == null ? null : pane.getScene().getWindow()); } catch (Exception ignored) {}
            urlField.requestFocus();
            urlField.positionCaret(urlField.getText() == null ? 0 : urlField.getText().length());
        }));

        dialog.setOnHidden(ev -> {
            dialogAlive[0] = false;
            sessionId.incrementAndGet();
            sizeReqId[0]++;
            addLinkDialogOpen = false;
            activeAddLinkUrlField = null;
            activeAddLinkDialog = null;
        });

        dialog.setResultConverter(btn -> btn);
        dialog.resultProperty().addListener((obs, oldRes, res) -> {
            if (res != downLoadBtn) return;
            dialogAlive[0] = false;
            sizeReqId[0]++;
            sessionId.incrementAndGet();
            stopSizeLoading.run();

            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            ContentType t = lastType[0];

            cb.saveLastDownloadFolder(folderField.getText());

            if (t == ContentType.VIDEO) {
                cb.addDownloadItemToList(url, folderField.getText(), modeCombo.getValue(), qualityCombo.getValue());
            } else if (t == ContentType.DIRECT_FILE) {
                cb.addDownloadItemToList(url, folderField.getText(), "Direct", "Auto");
            } else if (t == ContentType.PLAYLIST) {
                cb.setStatusText("Playlist detected (UI next): " + cb.shorten(url));
            } else {
                cb.setStatusText("Unsupported: " + cb.shorten(url));
            }
        });

        dialog.show();
    }

    // ===================== logic helpers =====================

    private static void fillQualityCombo(ComboBox<String> qualityCombo) {
        if (qualityCombo == null) return;
        // fallback safe list (no 4K/2K by default)
        qualityCombo.getItems().setAll(
                "Best",
                "────────",
                "1080p",
                "720p",
                "540p",
                "480p",
                "360p",
                "240p",
                "144p"
        );
        qualityCombo.getSelectionModel().select(0);
    }

    private void fillQualityComboFromHeights(ComboBox<String> qualityCombo, Set<Integer> heights) {
        if (qualityCombo == null) return;

        if (heights == null || heights.isEmpty()) {
            fillQualityCombo(qualityCombo);
            return;
        }

        List<Integer> sorted = new ArrayList<>(heights);
        sorted.removeIf(h -> h == null || h <= 0);
        sorted.sort(Comparator.reverseOrder());

        qualityCombo.getItems().clear();
        qualityCombo.getItems().add(cfg.QUALITY_BEST);
        qualityCombo.getItems().add(cfg.QUALITY_SEPARATOR);

        for (Integer h : sorted) qualityCombo.getItems().add(VideoQualityUtils.formatHeightLabel(h));
        qualityCombo.getSelectionModel().select(cfg.QUALITY_BEST);
    }

    private static void setGetButtonLoading(Button getBtn, boolean loading) {
        if (getBtn == null) return;

        if (loading) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setMaxSize(18, 18);
            spinner.setMouseTransparent(true);

            getBtn.setDisable(true);
            getBtn.setText("Getting...");
            getBtn.setGraphic(spinner);
            getBtn.setContentDisplay(ContentDisplay.RIGHT);
            getBtn.setMinWidth(130);
            getBtn.setPrefWidth(130);
            getBtn.setMaxWidth(130);
        } else {
            getBtn.setDisable(false);
            getBtn.setText("Get");
            getBtn.setGraphic(null);
            getBtn.setContentDisplay(ContentDisplay.CENTER);
            getBtn.setMinWidth(130);
            getBtn.setPrefWidth(130);
            getBtn.setMaxWidth(130);
        }
    }

    private List<String> buildAudioOptions() {
        ArrayList<String> out = new ArrayList<>();
        out.add(cfg.QUALITY_BEST);
        out.add(cfg.QUALITY_SEPARATOR);
        out.addAll(cfg.AUDIO_FORMATS);
        return out;
    }

    // ===== probing =====

    private static Long fetchCombinedSizeBytesWithYtDlpPrint(String url, String selector) {
        if (url == null || url.isBlank()) return null;
        try {
            List<String> args = new ArrayList<>();
            args.add("--no-warnings");
            args.add("--no-playlist");
            args.add("--skip-download");
            args.add("-f"); args.add(selector);
            args.add("--print"); args.add("%(filesize,filesize_approx)s");
            args.add(url.trim());

            String out = YtDlpManager.run(args);
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

    private static Long fetchSizeWithYtDlp(String url, String mode, String quality) {
        // keep existing behavior minimal: just reuse print approach for safety
        try {
            if (url == null || url.isBlank()) return null;
            String selector = "bv*+ba/b";
            if (mode != null && mode.toLowerCase().contains("audio")) selector = "bestaudio/best";

            if (quality != null && !quality.isBlank() && !quality.toLowerCase().contains("best")) {
                int h = VideoQualityUtils.parseHeight(quality);
                if (h > 0) selector = VideoQualityUtils.formatSelectorForHeight(h);
            }

            return fetchCombinedSizeBytesWithYtDlpPrint(url, selector);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long probeContentLength(String url) {
        if (url == null || url.isBlank()) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url.trim()).openConnection();
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

    private static String formatBytesDecimal(long bytes) {
        if (bytes <= 0) return "—";
        double b = bytes;
        double kb = 1000.0;
        double mb = kb * 1000.0;
        double gb = mb * 1000.0;

        if (b >= gb) return String.format(Locale.ROOT, "%.2f GB", b / gb);
        if (b >= mb) return String.format(Locale.ROOT, "%.1f MB", b / mb);
        if (b >= kb) return String.format(Locale.ROOT, "%.0f KB", b / kb);
        return bytes + " B";
    }
}
