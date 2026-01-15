package com.grabx.app.grabx;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.ui.components.HoverBubble;
import com.grabx.app.grabx.ui.components.NoSelectionModel;
import com.grabx.app.grabx.ui.probe.ProbeQualitiesResult;
import javafx.scene.layout.HBox;

import java.awt.Desktop;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;

import com.grabx.app.grabx.core.model.probe.AudioProbeService;
import com.grabx.app.grabx.core.model.probe.VideoProbeService;
import com.grabx.app.grabx.ui.components.ScrollbarAutoHide;
import com.grabx.app.grabx.ui.probe.AudioFormatInfo;
import com.grabx.app.grabx.ui.probe.ProbeAudioResult;
import com.grabx.app.grabx.ui.sidebar.SidebarItem;
import com.grabx.app.grabx.ui.playlist.PlaylistEntry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
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
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.File;
import java.util.Optional;

public class MainController {
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
            // if queue is full, run in caller thread would block UI; so we discard and try later
            new ThreadPoolExecutor.DiscardPolicy()
    );

    private static final Map<String, ProbeQualitiesResult> PLAYLIST_PROBE_CACHE = new ConcurrentHashMap<>();
    // Cache thumbnails to avoid re-downloading when cells are recycled
    private static final Map<String, Image> PLAYLIST_THUMB_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PLAYLIST_PROBE_INFLIGHT = ConcurrentHashMap.newKeySet();
    // Avoid creating multiple Image downloads for the same thumbnail when cells are recycled
    private static final Set<String> PLAYLIST_THUMB_INFLIGHT = ConcurrentHashMap.newKeySet();


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
                    ProbeQualitiesResult pr = probeQualitiesWithSizes(videoUrl);
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

    private final ObservableList<DownloadRow> downloadItems = FXCollections.observableArrayList();

    // ========= In-scene hover tooltip (no jitter) =========
    private Pane hoverLayer;
    private HoverBubble hoverBubble;
    // Pending tooltips until hoverBubble is ready (scene/root not ready during initialize)
    private final java.util.List<javafx.util.Pair<Button, String>> pendingTooltips = new java.util.ArrayList<>();
    private volatile boolean hoverBubbleReady = false;



    // ========= Actions =========

    @FXML
    public void onAddLink(ActionEvent event) {
        String clip = readClipboardTextSafe();
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
    public void onPauseAll(ActionEvent actionEvent) {
        if (statusText != null) statusText.setText("Pause all");
    }

    @FXML
    public void onResumeAll(ActionEvent actionEvent) {
        if (statusText != null) statusText.setText("Resume all");
    }

    @FXML
    public void onClearAll(ActionEvent actionEvent) {
        if (statusText != null) statusText.setText("Clear all");
    }

    // ======== yt-dlp probing (available qualities) ========

//    private static final Pattern YTDLP_HEIGHT_P = Pattern.compile("\\b(\\d{3,4})p\\b");
//    private static final Pattern YTDLP_HEIGHT_X = Pattern.compile("\\b\\d{3,4}x(\\d{3,4})\\b");


    // Matches: 1080p, 1080p60, 2160p, 2160p60 ... (yt-dlp often prints p60 without WxH)
    private static final Pattern YTDLP_HEIGHT_P = Pattern.compile("\\b(\\d{3,4})p(?:\\d{1,3})?\\b");

    // Matches: 1920x1080, 3840x2160 ...
    private static final Pattern YTDLP_HEIGHT_X = Pattern.compile("\\b\\d{3,4}x(\\d{3,4})\\b");

    private static Set<Integer> probeHeightsWithYtDlp(String url) {
        Set<Integer> heights = new HashSet<>();
        if (url == null || url.isBlank()) return heights;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "-F",
                    "--no-warnings",
                    "--no-playlist",
                    url
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = br.readLine()) != null) {

                    // ex: 1920x1080
                    Matcher mx = YTDLP_HEIGHT_X.matcher(line);
                    while (mx.find()) {
                        int h = normalizeHeight(safeParseInt(mx.group(1)));
                        if (h > 0) heights.add(h);
                    }

                    // ex: 1080p / 1080p60 / 2160p60 ...
                    Matcher mp = YTDLP_HEIGHT_P.matcher(line);
                    while (mp.find()) {
                        int h = normalizeHeight(safeParseInt(mp.group(1)));
                        if (h > 0) heights.add(h);
                    }
                }
            }

            p.waitFor();
        } catch (Exception ignored) {
        }

        // keep ONLY ladder values
        return normalizeHeights(heights);
    }

    private static final Pattern YTDLP_SIZE = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)(KiB|MiB|GiB)\\b");

    private static String youtubeWatchUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        return "https://www.youtube.com/watch?v=" + videoId;
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

    private static String buildMetaLine(com.grabx.app.grabx.ui.playlist.PlaylistEntry it) {
        if (it == null) return "";
        String q = it.getQuality();
        String sz = it.getSizeForQuality(q);
        if (sz == null || sz.isBlank()) return q;        // Best quality غالبًا بدون حجم
        return q + " \u2022 " + sz; // "•"
    }

    private static int parseHeightFromLabel(String label) {
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


    /**
     * Probes ONLY available heights + approx sizes from yt-dlp -F for a SINGLE VIDEO url.
     * Returns:
     * - qualities list (Best + separator + available)
     * - size map: qualityLabel -> "~120 MB"
     */
    private static ProbeQualitiesResult probeQualitiesWithSizes(String url) {
        java.util.Set<Integer> heights = new java.util.HashSet<>();
        java.util.Map<Integer, String> sizeByHeight = new java.util.HashMap<>();

        if (url == null || url.isBlank()) return new ProbeQualitiesResult(heights, sizeByHeight);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "-F",
                    "--no-warnings",
                    "--no-playlist",
                    url
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = br.readLine()) != null) {

                    // height from "1920x1080"
                    Matcher mx = YTDLP_HEIGHT_X.matcher(line);
                    while (mx.find()) {
                        int h = normalizeHeight(safeParseInt(mx.group(1)));
                        if (h > 0) {
                            heights.add(h);
                            sizeByHeight.putIfAbsent(h, approxSizeTextFromLine(line));
                        }
                    }

                    // height from "1080p" / "2160p60" ...
                    Matcher mp = YTDLP_HEIGHT_P.matcher(line);
                    while (mp.find()) {
                        int h = normalizeHeight(safeParseInt(mp.group(1)));
                        if (h > 0) {
                            heights.add(h);
                            sizeByHeight.putIfAbsent(h, approxSizeTextFromLine(line));
                        }
                    }
                }
            }

            p.waitFor();
        } catch (Exception ignored) {
        }

        heights = normalizeHeights(heights);
        sizeByHeight.keySet().retainAll(heights);
        return new ProbeQualitiesResult(heights, sizeByHeight);
    }

//    private static final class ProbeQualitiesResult {
//        final java.util.Set<Integer> heights;
//        final java.util.Map<Integer, String> sizeByHeight;
//
//        ProbeQualitiesResult(java.util.Set<Integer> heights, java.util.Map<Integer, String> sizeByHeight) {
//            this.heights = heights;
//            this.sizeByHeight = sizeByHeight;
//        }
//    }


    private static int safeParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

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

        installTooltips();
        setupHoverBubbleLayer();

        // ✅ Make hover/press work on the whole Button (not only the icon node)
        normalizeIconButton(pauseAllButton);
        normalizeIconButton(resumeAllButton);
        normalizeIconButton(clearAllButton);
        normalizeIconButton(addLinkButton);
        normalizeIconButton(settingsButton);

        // Main downloads list (center)
        ensureDownloadsListView();

        setupClipboardAutoPaste();

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

            // TODO لاحقاً: applyFilter(newV.getKey());
        });

        Platform.runLater(() -> {
            String clip = readClipboardTextSafe();
            if (!isHttpUrl(clip)) return;

            lastClipboardText = clip;

            UI_DELAY_EXEC.schedule(() -> Platform.runLater(() -> openAddLinkDialogDeferred(clip)),
                    350, TimeUnit.MILLISECONDS);
        });
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

    // ========= Tooltips (stable + no flicker) =========

    private void installTooltips() {
        installTooltip(pauseAllButton, "Pause all");
        installTooltip(resumeAllButton, "Resume all");
        installTooltip(clearAllButton, "Clear all");
        installTooltip(addLinkButton, "Add link");
        installTooltip(settingsButton, "Settings");
    }

    private void installTooltip(Button btn, String text) {
        if (btn == null) return;

        // If bubble not ready yet, queue it and apply once the scene is ready.
        if (hoverBubble == null || !hoverBubbleReady) {
            // keep latest text on the button
            btn.getProperties().put("gx-hover-text", text);
            pendingTooltips.add(new javafx.util.Pair<>(btn, text));
            Platform.runLater(this::flushPendingTooltips);
            return;
        }

        hoverBubble.install(btn, text);
    }

    private void flushPendingTooltips() {
        if (hoverBubble == null) return;
        hoverBubbleReady = true;

        // Install queued tooltips (dedupe by using the latest text stored on the button)
        for (var p : pendingTooltips) {
            Button b = p.getKey();
            if (b == null) continue;
            String txt = (String) b.getProperties().get("gx-hover-text");
            if (txt == null) txt = p.getValue();
            try {
                hoverBubble.install(b, txt);
            } catch (Exception ignored) {
            }
        }
        pendingTooltips.clear();
    }

    // ========= Custom in-scene tooltip bubble (no Popup/Tooltip jitter) =========
    private void setupHoverBubbleLayer() {
        if (root == null) return;

        // Scene may be null during initialize, so listen once.
        root.sceneProperty().addListener((obs, oldSc, newSc) -> {
            // Scene can be null during transitions / rebuilds
            if (newSc == null) return;

            // Ensure tooltip CSS is available (do this AFTER null-check)
            try {
                var cssUrl = getClass().getResource("/com/grabx/app/grabx/styles/buttons.css");
                if (cssUrl != null) {
                    String css = cssUrl.toExternalForm();
                    if (!newSc.getStylesheets().contains(css)) {
                        newSc.getStylesheets().add(css);
                    }
                }
            } catch (Exception ignored) {
            }

            Platform.runLater(() -> {
                try {
                    // If we already created the overlay & bubble, do nothing
                    if (hoverLayer != null && hoverBubble != null) {
                        // Still make sure queued tooltips are flushed once scene becomes ready
                        hoverBubbleReady = true;
                        flushPendingTooltips();
                        return;
                    }

                    javafx.scene.Parent currentRoot = newSc.getRoot();

                    if (currentRoot instanceof javafx.scene.layout.StackPane sp) {
                        hoverLayer = new Pane();
                        // Do NOT participate in layout; we only use it as an overlay.
                        hoverLayer.setManaged(false);
                        // Make the layer always cover the whole scene (prevents being behind/under other nodes)
                        hoverLayer.prefWidthProperty().bind(sp.widthProperty());
                        hoverLayer.prefHeightProperty().bind(sp.heightProperty());
                        hoverLayer.setMinSize(0, 0);
                        hoverLayer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                        javafx.scene.layout.StackPane.setAlignment(hoverLayer, javafx.geometry.Pos.TOP_LEFT);
                        hoverLayer.setPickOnBounds(false);
                        hoverLayer.setMouseTransparent(true);
                        hoverLayer.getStyleClass().add("gx-hover-layer");
                        // Ensure it is ALWAYS on top
                        sp.getChildren().add(hoverLayer);
                        hoverLayer.toFront();
                        hoverLayer.setViewOrder(-10_000);
                    } else {
                        javafx.scene.layout.StackPane wrapper = new javafx.scene.layout.StackPane();
                        wrapper.getChildren().add(currentRoot);

                        hoverLayer = new Pane();
                        // Do NOT participate in layout; we only use it as an overlay.
                        hoverLayer.setManaged(false);
                        // Make the layer always cover the whole scene (prevents being behind/under other nodes)
                        hoverLayer.prefWidthProperty().bind(wrapper.widthProperty());
                        hoverLayer.prefHeightProperty().bind(wrapper.heightProperty());
                        hoverLayer.setMinSize(0, 0);
                        hoverLayer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                        javafx.scene.layout.StackPane.setAlignment(hoverLayer, javafx.geometry.Pos.TOP_LEFT);
                        hoverLayer.setPickOnBounds(false);
                        hoverLayer.setMouseTransparent(true);
                        hoverLayer.getStyleClass().add("gx-hover-layer");
                        // Ensure it is ALWAYS on top
                        wrapper.getChildren().add(hoverLayer);
                        hoverLayer.toFront();
                        hoverLayer.setViewOrder(-10_000);

                        newSc.setRoot(wrapper);
                    }

                    hoverBubble = new HoverBubble(hoverLayer);
                    hoverBubbleReady = true;
                    flushPendingTooltips();

                    // Ensure topbar tooltips are installed too (they can be queued during initialize)
                    installTooltips();
                } catch (Exception ignored) {
                }
            });
        });
    }


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

    private static final String QUALITY_BEST = "Best quality (Recommended)";
    private static final String QUALITY_SEPARATOR = "──────────────";
    private static final String QUALITY_CUSTOM = "Custom (Mixed)";
    private static final String MODE_VIDEO = "Video";
    private static final String MODE_AUDIO = "Audio only";

    private static final String AUDIO_BEST = "Best audio (Recommended)";
    private static final String AUDIO_DEFAULT_FORMAT = "mp3";
    private static final java.util.List<String> AUDIO_FORMATS = java.util.List.of(
            "m4a", "mp3", "opus", "aac", "wav", "flac"
    );

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
        return h + "p";
    }

    // Normalize slightly-off heights from yt-dlp (e.g., 1434 -> 1440, 1076 -> 1080, 718 -> 720)
    private static int normalizeHeight(int raw) {
        if (raw <= 0) return raw;

        int[] ladder = {2160, 1440, 1080, 720, 480, 360, 240, 144};

        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int std : ladder) {
            int diff = Math.abs(raw - std);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = std;
            }
        }

        int tol;
        if (best >= 1440) tol = 40;
        else if (best >= 720) tol = 20;
        else tol = 10;

        return (bestDiff <= tol) ? best : raw;
    }

    private static java.util.Set<Integer> normalizeHeights(java.util.Set<Integer> rawHeights) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (rawHeights == null) return out;

        for (Integer h : rawHeights) {
            if (h == null) continue;
            int n = normalizeHeight(h);
            if (n > 0) out.add(n);
        }

        // Keep ONLY standard ladder values
        int[] ladder = {2160, 1440, 1080, 720, 480, 360, 240, 144};
        java.util.Set<Integer> filtered = new java.util.HashSet<>();
        for (int std : ladder) {
            if (out.contains(std)) filtered.add(std);
        }
        return filtered;
    }

    // ========= Add Link dialog =========

    // ========== Add Link dialog state tracking for clipboard auto-paste ==========
    private boolean addLinkDialogOpen = false;
    private TextField activeAddLinkUrlField = null;
    private String pendingAddLinkPrefillUrl = null;
    // Keep a strong reference so the poll Timeline doesn't get GC'ed
    private javafx.animation.Timeline clipboardPollTimeline;

    private void showAddLinkDialog() {
        showAddLinkDialog(null);
    }

    private void showAddLinkDialog(String prefillUrl) {
        if (addLinkDialogOpen) {
            // If already open, just update the field if we can.
            if (prefillUrl != null && isHttpUrl(prefillUrl) && activeAddLinkUrlField != null) {
                activeAddLinkUrlField.setText(prefillUrl.trim());
                activeAddLinkUrlField.positionCaret(activeAddLinkUrlField.getText().length());
                Platform.runLater(activeAddLinkUrlField::requestFocus);
            }
            return;
        }

        addLinkDialogOpen = true;
        activeAddLinkUrlField = null;

//        Dialog<ButtonType> dialog = new Dialog<>();
        Dialog<ButtonType> dialog = new Dialog<>();
        activeAddLinkDialog = dialog;
        dialog.setTitle("Add Link");
        dialog.setHeaderText(null);

        try {
            if (root != null && root.getScene() != null && root.getScene().getWindow() != null) {
                dialog.initOwner(root.getScene().getWindow());
                dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);
            }
        } catch (Exception ignored) {}


        DialogPane pane = dialog.getDialogPane();

        installClickToDefocus(pane);

        // ✅ class + inline fallback (prevents white flash even if CSS applies late)
        pane.getStyleClass().add("gx-dialog");
        pane.setStyle("-fx-background-color: #121826;");
        pane.setPadding(Insets.EMPTY);

        // ✅ Load CSS (same as app)
        pane.getStylesheets().addAll(
                getClass().getResource("/com/grabx/app/grabx/styles/theme-base.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/layout.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/buttons.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/sidebar.css").toExternalForm()
        );

        // ✅ Fix initial white flash: apply CSS/layout BEFORE first render
        dialog.setOnShowing(ev -> {
            Scene sc = pane.getScene();
            if (sc != null) {
                sc.setFill(Color.web("#121826"));
                sc.getRoot().setStyle("-fx-background-color: #121826;");
            }
            pane.applyCss();
            pane.layout();
        });

        // Primary button = Add & Start (no checkbox)
        ButtonType addStartBtn = new ButtonType("Add & Start", ButtonBar.ButtonData.OK_DONE);

        pane.getButtonTypes().setAll(ButtonType.CANCEL, addStartBtn);

        Button addStartButton = (Button) pane.lookupButton(addStartBtn);
        addStartButton.getStyleClass().addAll("gx-btn", "gx-btn-primary");

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

        // GET button (analyze)
        Button getBtn = new Button("Get");
        getBtn.getStyleClass().addAll("gx-btn", "gx-btn-ghost");//gx-input

        getBtn.setMinWidth(90);

        // Mode + Quality (disabled until analyzed as VIDEO)
        ComboBox<String> modeCombo = new ComboBox<>();
        // Use the same constants everywhere so comparisons never break
        modeCombo.getItems().setAll(MODE_VIDEO, MODE_AUDIO);
        modeCombo.getSelectionModel().select(MODE_VIDEO);
        modeCombo.getStyleClass().add("gx-combo");

        ComboBox<String> qualityCombo = new ComboBox<>();
        qualityCombo.getStyleClass().add("gx-combo");
        fillQualityCombo(qualityCombo);
        qualityCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setDisable(QUALITY_SEPARATOR.equals(item));
                setOpacity(QUALITY_SEPARATOR.equals(item) ? 0.55 : 1.0);
            }
        });
        // also for the button cell (selected display)
        qualityCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });

        // Folder
        TextField folderField = new TextField(System.getProperty("user.home") + File.separator + "Downloads");
        folderField.setEditable(false);
        folderField.getStyleClass().add("gx-input");

        Button browseBtn = new Button("Browse");
        browseBtn.getStyleClass().addAll("gx-btn", "gx-btn-ghost");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Download Folder");
            File selected = chooser.showDialog(pane.getScene().getWindow());
            if (selected != null) folderField.setText(selected.getAbsolutePath());
        });

        // Info / status line inside dialog
        Label info = new Label("Paste a link then click Get.");
        info.getStyleClass().add("gx-text-muted");
        info.setWrapText(true);
        // Keep a consistent default color; errors will override temporarily
        info.setTextFill(Color.web("#9aa4b2"));

        // Rows
        int r = 0;
        grid.add(new Label("URL"), 0, r);
        grid.add(urlField, 1, r);
        grid.add(getBtn, 2, r);
        GridPane.setHgrow(urlField, Priority.ALWAYS);

        r++;
        Label modeLbl = new Label("Mode");
        grid.add(modeLbl, 0, r);
        grid.add(modeCombo, 1, r);
        GridPane.setHgrow(modeCombo, Priority.ALWAYS);

        r++;
        Label qualityLbl = new Label("Quality");
        grid.add(qualityLbl, 0, r);
        grid.add(qualityCombo, 1, r);
        GridPane.setHgrow(qualityCombo, Priority.ALWAYS);

        r++;
        grid.add(new Label("Folder"), 0, r);
        grid.add(folderField, 1, r);
        grid.add(browseBtn, 2, r);
        GridPane.setHgrow(folderField, Priority.ALWAYS);

        r++;
        grid.add(info, 1, r, 2, 1);

        // Default: hide/disable mode+quality until analysis says VIDEO
        modeCombo.setDisable(true);
        qualityCombo.setDisable(true);
        setManagedVisible(modeLbl, true);
        setManagedVisible(modeCombo, true);
        setManagedVisible(qualityLbl, true);
        setManagedVisible(qualityCombo, true);

        // Disable Add & Start until we have analyzed successfully
        Button okBtn = (Button) pane.lookupButton(addStartBtn);
        okBtn.setDisable(true);

        // keep last analysis
        final ContentType[] lastType = {ContentType.UNSUPPORTED};

        Runnable applyTypeToUi = () -> {
            ContentType t = lastType[0];

            if (t == ContentType.VIDEO) {
                modeCombo.setDisable(false);
                qualityCombo.setDisable(false);
                info.setText("Detected: Video. Choose mode/quality then Add & Start.");
                info.setTextFill(Color.web("#9aa4b2"));
                okBtn.setDisable(false);
            } else if (t == ContentType.PLAYLIST) {
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Detected: Playlist. Opening Playlist screen...");
                info.setTextFill(Color.web("#9aa4b2"));
                // Add is handled from the Playlist screen
                okBtn.setDisable(true);
            } else if (t == ContentType.DIRECT_FILE) {
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Detected: Direct file/link. Ready to Add & Start.");
                info.setTextFill(Color.web("#9aa4b2"));
                okBtn.setDisable(false);
            } else {
                // Unsupported / invalid / empty
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Unsupported or invalid URL.");
                info.setTextFill(Color.web("#ff4d4d"));
                okBtn.setDisable(true);
            }
        };

        getBtn.setOnAction(e -> {
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            if (url.isBlank()) {
                // Empty URL -> guide the user (not “unsupported”)
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                fillQualityCombo(qualityCombo);
                info.setText("Paste a link then click Get.");
                info.setTextFill(Color.web("#ff4d4d"));
                okBtn.setDisable(true);
//                lastType[0] = ContentType.UNSUPPORTED;
                // ===== Level 1 probe =====
                // 1) Try yt-dlp title: if it works, it's a supported media URL.
                String mediaTitle = fetchTitleWithYtDlp(url);

                if (mediaTitle != null && !mediaTitle.isBlank()) {

                    // Decide type (playlist/video) using your existing analyzer.
                    lastType[0] = analyzeUrlType(url);
                    if (lastType[0] == ContentType.UNSUPPORTED) {
                        // If yt-dlp can print title, treat as VIDEO as a safe fallback.
                        lastType[0] = ContentType.VIDEO;
                    }

                    info.setText("Detected media: " + mediaTitle);
                    info.setTextFill(Color.web("#9AA4B2"));

                } else {
                    // 2) If yt-dlp doesn't support it, try direct file probe (HEAD)
                    DirectFileProbe df = probeDirectFile(url);
                    if (df != null && df.isFile) {
                        lastType[0] = ContentType.DIRECT_FILE;

                        String name = (df.fileName != null && !df.fileName.isBlank())
                                ? df.fileName
                                : "Direct file";

                        info.setText("Detected direct file: " + name);
                        info.setTextFill(Color.web("#9AA4B2"));
                    } else {
                        lastType[0] = ContentType.UNSUPPORTED;
                    }
                }
                return;
            }
            lastType[0] = analyzeUrlType(url);

            // If it's a single video, probe available heights and rebuild the quality list accordingly
            if (lastType[0] == ContentType.VIDEO) {
                info.setText("Analyzing formats...");
                info.setTextFill(Color.web("#9aa4b2"));

                new Thread(() -> {
                    Set<Integer> heights = probeHeightsWithYtDlp(url);

                    Platform.runLater(() -> {
                        // show only the heights that actually exist; fallback if empty
                        fillQualityComboFromHeights(qualityCombo, heights);
                        applyTypeToUi.run();
                    });
                }, "probe-qualities").start();

                return; // UI will update after probing
            }

            // ✅ Playlist: open playlist screen immediately
            if (lastType[0] == ContentType.PLAYLIST) {
                applyTypeToUi.run();
                openPlaylistWindow(url, folderField.getText());
                return;
            }

            applyTypeToUi.run();
        });

        // UX: pressing Enter in URL triggers Get
        urlField.setOnAction(e -> getBtn.fire());

        // If user edits URL after Get, require Get again
        urlField.textProperty().addListener((obs, oldV, newV) -> {
            lastType[0] = ContentType.UNSUPPORTED;
            okBtn.setDisable(true);
            modeCombo.setDisable(true);
            qualityCombo.setDisable(true);
            fillQualityCombo(qualityCombo);
            info.setText("Paste a link then click Get.");
            info.setTextFill(Color.web("#9aa4b2"));
        });

        pane.setContent(grid);
        pane.setPrefWidth(760);

        final String effectivePrefill = (prefillUrl != null && !prefillUrl.isBlank())
                ? prefillUrl.trim()
                : (pendingAddLinkPrefillUrl != null && !pendingAddLinkPrefillUrl.isBlank()
                ? pendingAddLinkPrefillUrl.trim()
                : null);

        if (effectivePrefill != null) {
            urlField.setText(effectivePrefill);
            pendingAddLinkPrefillUrl = null; // consume once
        }


        dialog.setOnShown(ev -> Platform.runLater(() -> {
            bringWindowToFront(pane.getScene() == null ? null : pane.getScene().getWindow());
            urlField.requestFocus();
            urlField.positionCaret(urlField.getText() == null ? 0 : urlField.getText().length());
        }));

        dialog.setOnHidden(ev -> {
            addLinkDialogOpen = false;
            activeAddLinkUrlField = null;
            activeAddLinkDialog = null;
        });

        // ===== IMPORTANT =====
        // Do NOT use showAndWait(): it blocks the JavaFX Application Thread.
        // Blocking prevents our clipboard poll/focus listeners from running,
        // so the URL field cannot live-update while the dialog is open.
        dialog.setResultConverter(btn -> btn);

        dialog.resultProperty().addListener((obs, oldRes, res) -> {
            if (res != addStartBtn) return;

            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            ContentType t = lastType[0];

            // Temporary behavior until we wire the real downloader engine:
            if (t == ContentType.VIDEO) {
                addDownloadItemToList(url, folderField.getText(), modeCombo.getValue(), qualityCombo.getValue());
            } else if (t == ContentType.DIRECT_FILE) {
                addDownloadItemToList(url, folderField.getText(), "Direct", "Auto");
            } else if (t == ContentType.PLAYLIST) {
                if (statusText != null) statusText.setText("Playlist detected (UI next): " + shorten(url));
            } else {
                if (statusText != null) statusText.setText("Unsupported: " + shorten(url));
            }
        });

        // Show modelessly so FX thread keeps running (clipboard auto-paste keeps working)
        dialog.show();
    }

    // ========= Main downloads list (center) =========
    private static final String ICON_FOLDER_OUTLINE =
            "M20 6h-8.17L10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 14H4V6h5.17l2 2H20v12z";


    private static final String ICON_FOLDER_OPEN =
            "M3 6.5C3 5.12 4.12 4 5.5 4H10L12 6H18.5C19.88 6 21 7.12 21 8.5V17.5C21 18.88 19.88 20 18.5 20H5.5C4.12 20 3 18.88 3 17.5V6.5Z";

    private static final String ICON_PAUSE =
            "M6 5h4v14H6V5zm8 0h4v14h-4V5z";

    private static final String ICON_PLAY =
            "M8 5v14l11-7L8 5z";

    private static final String ICON_CANCEL =
            "M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.3 19.71 2.89 18.29 9.17 12 2.89 5.71 4.3 4.29 10.59 10.6 16.89 4.29z";

    private static final String ICON_RETRY =
            "M12 5a7 7 0 1 1-6.32 4H3l3.5-3.5L10 9H7.76A5.5 5.5 0 1 0 12 6.5V5z";

    private static Node svgIcon(String path, double boxSize) {
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

    private static void setupSvgButton(Button b, String svgPath) {
        // Match Topbar icon buttons look
        b.getStyleClass().addAll("gx-icon-btn", "gx-task-action");
        b.setFocusTraversable(false);
        b.setText(null);
        b.setGraphic(svgIcon(svgPath, 34));

//        if (tooltipText != null && !tooltipText.isBlank()) {
//            Tooltip t = new Tooltip(tooltipText);
//            t.getStyleClass().add("gx-tooltip");
//            b.setTooltip(t);
//        }
    }


    private void ensureDownloadsListView() {
        // If FXML did not inject it, create it and mount it in the center.
        if (downloadsList == null) {
            downloadsList = new ListView<>();
            downloadsList.getStyleClass().add("gx-task-list");
            downloadsList.setStyle("-fx-background-color: transparent;");
            // If root is BorderPane and center is empty, mount it.
            try {
                if (root instanceof BorderPane bp && bp.getCenter() == null) {
                    bp.setCenter(downloadsList);
                }
            } catch (Exception ignored) {
            }
        }

        downloadsList.setItems(downloadItems);
        downloadsList.setFocusTraversable(false);

        downloadsList.setCellFactory(lv -> new ListCell<>() {
            private final Label title = new Label();
            private final Label meta = new Label();
            private final Label status = new Label();
            private final Label speed = new Label();
            private final Label eta = new Label();

            // Thumbnail (left)
            private final StackPane thumbBox = new StackPane();
            private final ImageView thumb = new ImageView();
            private final Label thumbPlaceholder = new Label("NO PREVIEW");

            private final ProgressBar bar = new ProgressBar(0);

            private final Button pauseBtn = new Button();
            private final Button resumeBtn = new Button();
            private final Button cancelBtn = new Button();
            private final Button folderBtn = new Button();
            private final Button retryBtn = new Button();

            private final HBox actions = new HBox(8);
            private final VBox textBox = new VBox(6);
            private final HBox headerRow = new HBox(12);
            private final HBox footerRow = new HBox(10);
            private final VBox card = new VBox(10);

            {
                setStyle("-fx-background-color: transparent;");

                title.getStyleClass().add("gx-task-title");
                title.setWrapText(false);

                meta.getStyleClass().add("gx-task-meta");
                status.getStyleClass().add("gx-task-status");
                speed.getStyleClass().add("gx-task-status");
                eta.getStyleClass().add("gx-task-status");

                bar.getStyleClass().add("gx-task-progress");
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.setPrefHeight(6);
                bar.setMinHeight(6);

                // Thumbnail
                thumb.setFitWidth(108);
                thumb.setFitHeight(66);
                thumb.setPreserveRatio(true);
                thumb.setPreserveRatio(true);
                thumb.setSmooth(true);

                thumbBox.getStyleClass().add("gx-task-thumb");
                thumbPlaceholder.getStyleClass().add("gx-task-thumb-placeholder");
                thumbBox.getChildren().addAll(thumb, thumbPlaceholder);

                applyRoundedClip(thumbBox, 14);

                // Icon buttons (SVG) — unified with topbar style
                setupSvgButton(pauseBtn, ICON_PAUSE);
                setupSvgButton(resumeBtn, ICON_PLAY);
                setupSvgButton(cancelBtn, ICON_CANCEL);
                cancelBtn.getStyleClass().add("cancel");
                cancelBtn.setGraphic(svgIcon(ICON_CANCEL, 28)); // بدل 34
                setupSvgButton(folderBtn, ICON_FOLDER_OPEN);
                setupSvgButton(retryBtn, ICON_RETRY);

                MainController.this.installTooltip(pauseBtn, "Pause download");
                MainController.this.installTooltip(resumeBtn, "Resume download");
                MainController.this.installTooltip(cancelBtn, "Cancel download");
                MainController.this.installTooltip(retryBtn, "Retry download");
                MainController.this.installTooltip(folderBtn, "Open folder");


                actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
                actions.setFillHeight(true);
                actions.setMinHeight(40);
                actions.getChildren().addAll(pauseBtn, resumeBtn, cancelBtn, folderBtn, retryBtn);

                textBox.getChildren().addAll(title, meta);
                HBox.setHgrow(textBox, Priority.ALWAYS);

                headerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                headerRow.getChildren().addAll(thumbBox, textBox, actions);

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                footerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                footerRow.getChildren().addAll(status, spacer, speed, eta);

                card.getStyleClass().add("gx-task-card");
                card.getChildren().addAll(headerRow, bar, footerRow);
                VBox.setVgrow(card, Priority.NEVER);

                // Actions (UI-only for now)
                pauseBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;
                    it.status.set("Paused");
                });

                resumeBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;
                    it.status.set("Downloading");
                });

                cancelBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;
                    it.status.set("Cancelled");
                    it.progress.set(0);
                });

                folderBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;
                    try {
                        File f = new File(it.folder);
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(f);
                        }
                    } catch (Exception ignored) {
                    }
                });

                retryBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;

                    // UI retry (engine wiring later): reset to queued and restart from 0
                    it.status.set("Queued");
                    it.progress.set(0);
                    it.speed.set("0 KB/s");
                    it.eta.set("--");

                    if (statusText != null) {
                        statusText.setText("Retry: " + it.title.get());
                    }
                });
            }

            @Override
            protected void updateItem(DownloadRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                // Thumbnail reset
                thumb.setImage(null);
                thumbPlaceholder.setVisible(true);

                title.textProperty().unbind();
                title.textProperty().bind(item.title);
                meta.setText(item.mode + " • " + item.quality + " • " + item.folder);

                final String turl = (item.thumbUrl == null) ? null : item.thumbUrl.get();
                if (turl != null && !turl.isBlank()) {

                    Image img = MAIN_THUMB_CACHE.get(turl);
                    if (img == null) {
                        img = new Image(turl, true);
                        MAIN_THUMB_CACHE.put(turl, img);
                    }

                    final Image imgRef = img;
                    thumb.setImage(imgRef);

                    Runnable applyCover = () -> {
                        DownloadRow now = getItem();
                        if (now == null) return;

                        String nowUrl = (now.thumbUrl == null) ? null : now.thumbUrl.get();
                        if (nowUrl == null || !nowUrl.equals(turl)) return;
                        if (imgRef.getException() != null) return;

                        applyCoverViewport(thumb, imgRef, 108, 66);
                        thumbPlaceholder.setVisible(false);
                    };

                    if (imgRef.getProgress() >= 1.0 && imgRef.getException() == null) {
                        applyCover.run();
                    } else {
                        thumbPlaceholder.setVisible(true);
                        imgRef.progressProperty().addListener((o, a, b) -> {
                            if (b != null && b.doubleValue() >= 1.0) {
                                Platform.runLater(applyCover);
                            }
                        });
                    }

                } else {
                    thumb.setViewport(null);
                    thumb.setImage(null);
                    thumbPlaceholder.setVisible(true);
                }


                bar.progressProperty().unbind();
                status.textProperty().unbind();
                speed.textProperty().unbind();
                eta.textProperty().unbind();

                bar.progressProperty().bind(item.progress);
                status.textProperty().bind(item.status);
                speed.textProperty().bind(item.speed);
                eta.textProperty().bind(item.eta);

                // Toggle which buttons appear based on status (hide instead of disable)
                String s = item.status.get();
                String sl = (s == null) ? "" : s.toLowerCase();

                boolean isQueued      = sl.contains("queue");
                boolean isDownloading = sl.contains("down");
                boolean isPaused      = sl.contains("pause");
                boolean isCompleted   = sl.contains("complete");
                boolean isFailed      = sl.contains("fail") || sl.contains("error") || sl.contains("cancel");

                // Helper: hide removes the button AND its space
                java.util.function.BiConsumer<Button, Boolean> showBtn = (btn, show) -> {
                    btn.setVisible(show);
                    btn.setManaged(show);
                };

                // Default: hide all, then enable what makes sense
                showBtn.accept(pauseBtn, false);
                showBtn.accept(resumeBtn, false);
                showBtn.accept(cancelBtn, false);
                showBtn.accept(folderBtn, true);   // folder is always useful
                showBtn.accept(retryBtn, false);

                if (isDownloading) {
                    showBtn.accept(pauseBtn, true);
                    showBtn.accept(cancelBtn, true);
                } else if (isPaused) {
                    showBtn.accept(resumeBtn, true);
                    showBtn.accept(cancelBtn, true);
                } else if (isQueued) {
                    // queued: allow cancel (and folder)
                    showBtn.accept(cancelBtn, true);
                } else if (isFailed) {
                    // failed/cancelled: retry is the main action
                    showBtn.accept(retryBtn, true);
                } else if (isCompleted) {
                    // completed: only folder (already shown)
                }

                // Safety: never show retry while downloading
                if (isDownloading) {
                    showBtn.accept(retryBtn, false);
                }

                setGraphic(card);
            }
        });

        // Make it look nicer without selection highlight
        downloadsList.setSelectionModel(new NoSelectionModel<>());
    }




    private String fetchTitleWithYtDlp(String url) {
        if (url == null || url.isBlank()) return null;

        try {
            // Force UTF-8 output from yt-dlp on ALL platforms (fixes Windows garbled Arabic).
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--no-warnings",
                    "--no-playlist",
                    "--skip-download",
                    "--encoding", "utf-8",
                    "--print", "title",
                    url.trim()
            );

            // Extra hardening for Windows/Python stdout encoding
            try {
                pb.environment().put("PYTHONIOENCODING", "utf-8");
                pb.environment().put("PYTHONUTF8", "1");
            } catch (Exception ignored) {
            }

            pb.redirectErrorStream(true);
            Process p = pb.start();

            String best = null;

            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = br.readLine()) != null) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;

                    String sl = s.toLowerCase();
                    if (sl.startsWith("warning:")) continue;

                    // If yt-dlp prints an error line, treat as unsupported.
                    if (sl.startsWith("error:")) {
                        best = null;
                        break;
                    }

                    best = s; // keep last plausible title
                }
            }

            int code = p.waitFor();
            if (code != 0) return null;
            if (best == null || best.isBlank()) return null;

            return best;

        } catch (Exception ignored) {
            return null;
        }
    }

    // ========== Level-1 URL support probing (Media -> Direct file -> Unsupported) ==========

    private static final class DirectFileProbe {
        final boolean isFile;
        final String contentType;
        final String fileName;

        DirectFileProbe(boolean isFile, String contentType, String fileName) {
            this.isFile = isFile;
            this.contentType = contentType;
            this.fileName = fileName;
        }
    }

    /**
     * Direct file probe:
     * - HEAD first
     * - Treat as file if Content-Disposition contains attachment/filename OR Content-Type is not text/html.
     */
    private DirectFileProbe probeDirectFile(String url) {
        if (url == null || url.isBlank()) return new DirectFileProbe(false, null, null);

        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(6500);
            conn.setRequestMethod("HEAD");
            conn.connect();

            String ct = conn.getContentType();
            String cd = conn.getHeaderField("Content-Disposition");

            boolean hasAttachment = false;
            String fileName = null;

            if (cd != null) {
                String lcd = cd.toLowerCase();
                hasAttachment = lcd.contains("attachment") || lcd.contains("filename=");
                fileName = extractFilenameFromContentDisposition(cd);
            }

            boolean notHtml = (ct != null) && !ct.toLowerCase().startsWith("text/html");

            if (fileName == null || fileName.isBlank()) {
                fileName = extractFilenameFromUrlPath(url);
            }

            boolean isFile = hasAttachment || notHtml;
            return new DirectFileProbe(isFile, ct, fileName);

        } catch (Exception ignored) {
            return new DirectFileProbe(false, null, null);
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static String extractFilenameFromContentDisposition(String cd) {
        if (cd == null) return null;
        try {
            String[] parts = cd.split(";");
            for (String p : parts) {
                String s = p.trim();
                if (s.toLowerCase().startsWith("filename=")) {
                    String v = s.substring("filename=".length()).trim();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                        v = v.substring(1, v.length() - 1);
                    }
                    return v;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractFilenameFromUrlPath(String url) {
        if (url == null) return null;
        try {
            java.net.URL u = new java.net.URL(url);
            String path = u.getPath();
            if (path == null || path.isBlank()) return null;
            int slash = path.lastIndexOf('/');
            String name = (slash >= 0) ? path.substring(slash + 1) : path;
            if (name == null) return null;
            name = name.trim();
            if (name.isBlank()) return null;
            return name;
        } catch (Exception ignored) {}
        return null;
    }


    private void setupIconButton(Button b, String fallbackText, String tooltipText) {
        b.getStyleClass().addAll("gx-btn", "gx-btn-ghost", "gx-task-action");
        b.setFocusTraversable(false);
        b.setText(fallbackText);
        if (tooltipText != null && !tooltipText.isBlank()) {
            this.installTooltip(b, tooltipText);
        }
    }


    private void addDownloadItemToList(String url, String folder, String mode, String quality) {
        ensureDownloadsListView();

        // 1) عنوان مبدئي
        String initialTitle = "Loading ..."; // subtle loading placeholder
        if (url == null || url.isBlank()) {
            initialTitle = "(empty link)";
        }

        // 2) أنشئ صف التحميل
        DownloadRow row = new DownloadRow(url, initialTitle, folder, mode, quality);

        // 3) أضِف في أعلى القائمة
        downloadItems.add(0, row);

        // 4) حدّث شريط الحالة
        if (statusText != null) {
            statusText.setText("Queued: " + row.title.get());
        }

        // 5) جلب العنوان الحقيقي بالخلفية (بدون تجميد الواجهة)
        if (url != null && !url.isBlank()) {
            new Thread(() -> {
                String realTitle = fetchTitleWithYtDlp(url);
                Platform.runLater(() -> {
                    if (realTitle != null && !realTitle.isBlank()) {
                        row.title.set(realTitle);
                        if (statusText != null) {
                            statusText.setText("Queued: " + realTitle);
                        }
                    } else {
                        // Fallback: don't leave it as dots forever
                        String fallback = shorten(url);
                        if (fallback == null || fallback.isBlank()) fallback = "Unknown title";
                        row.title.set(fallback);
                        if (statusText != null) {
                            statusText.setText("Queued: " + fallback);
                        }
                    }
                });
            }, "yt-title-probe").start();
        }
    }


    // --- Thumbnail helpers and cache ---
    private static final java.util.Map<String, javafx.scene.image.Image> MAIN_THUMB_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String extractYoutubeId(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;

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

        return null;
    }

    public static String thumbFromUrl(String url) {
        String id = extractYoutubeId(url);
        if (id == null || id.isBlank()) return null;
        return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
    }


    // === Thumbnail rendering helpers (cover crop + rounded clip) ===
    private static void applyCoverViewport(ImageView iv, Image img, double targetW, double targetH) {
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

    private static void applyRoundedClip(Region region, double arc) {
        if (region == null) return;

        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(arc * 2);
        clip.setArcHeight(arc * 2);
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        region.setClip(clip);
    }


    // ========= Playlist Screen (v1 - lightweight) =========

    private void openPlaylistWindow(String playlistUrl, String folder) {

        Stage stage = new Stage();
        stage.setTitle("Playlist");
//        stage.initModality(Modality.APPLICATION_MODAL);

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

        // Global quality selector (applies to selected items, keeps per-item override possible)
        Label globalQLabel = new Label("Quality for all");
        globalQLabel.getStyleClass().add("gx-text-muted");

        Label globalModeLabel = new Label("Mode for all");
        globalModeLabel.getStyleClass().add("gx-text-muted");

        ComboBox<String> globalModeCombo = new ComboBox<>();
        globalModeCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality");
        globalModeCombo.setPrefWidth(140);
        globalModeCombo.getItems().setAll(MODE_VIDEO, MODE_AUDIO);

        ComboBox<String> globalQualityCombo = new ComboBox<>();
        globalQualityCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality");
        globalQualityCombo.setPrefWidth(160);

        // standard list; we will map to closest supported per item
        fillQualityCombo(globalQualityCombo);

        // separator behavior
        globalQualityCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
//                setDisable(QUALITY_SEPARATOR.equals(item));
//                setOpacity(QUALITY_SEPARATOR.equals(item) ? 0.55 : 1.0);
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

        // NOTE: we DO NOT bind; we only update these on USER changes to the global combos.

        HBox globalRow = new HBox(10);
        globalRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
//        globalRow.getChildren().addAll(globalQLabel, globalQualityCombo);
        globalRow.getChildren().addAll(globalModeLabel, globalModeCombo, globalQLabel, globalQualityCombo);


        Label status = new Label("Loading playlist...");
        status.getStyleClass().add("gx-text-muted");

        ListView<PlaylistEntry> list = new ListView<>();
        list.getStyleClass().add("gx-playlist-list");   // لا تضيف gx-list
        list.setStyle("-fx-background-color: transparent;");
        ObservableList<PlaylistEntry> items = FXCollections.observableArrayList();
        list.setItems(items);

        list.setPrefHeight(420);
        list.setFocusTraversable(false);
        // Prevent default ListView selection highlight (blue outlines)
        list.setSelectionModel(new NoSelectionModel<>());

        // Throttle refreshes (so 100 callbacks won't spam list.refresh())
        PauseTransition refreshThrottle = new PauseTransition(Duration.millis(140));
        Runnable requestRefresh = () -> {
            refreshThrottle.stop();
            refreshThrottle.setOnFinished(ev -> list.refresh());
            refreshThrottle.playFromStart();
        };

        javafx.beans.property.BooleanProperty anyQualityPopupOpen =
                new javafx.beans.property.SimpleBooleanProperty(false);
        java.util.concurrent.atomic.AtomicBoolean pendingRefresh =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        java.util.concurrent.atomic.AtomicBoolean pendingMixedUpdate =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicReference<Runnable> updateGlobalMixedStateRef = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable requestRefreshSafe = () -> {
            if (anyQualityPopupOpen.get()) {
                pendingRefresh.set(true);
                return;
            }
            refreshThrottle.stop();
            refreshThrottle.setOnFinished(ev -> list.refresh());
            refreshThrottle.playFromStart();
        };

        // Track popup state (so list.refresh() doesn't close an open ComboBox)
        globalQualityCombo.showingProperty().addListener((obs, was, isNow) -> anyQualityPopupOpen.set(isNow));
        // Run pending UI refresh/mixed update after any quality popup closes
        anyQualityPopupOpen.addListener((o, was, isNow) -> {
            if (!isNow) {
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
        });

// Stop background prefetch when window closes
        java.util.concurrent.atomic.AtomicBoolean stopPrefetch = new java.util.concurrent.atomic.AtomicBoolean(false);
        stage.setOnHidden(ev -> stopPrefetch.set(true));

        // ===== Union of discovered heights across playlist for the GLOBAL combo =====
        java.util.Set<Integer> globalHeightsUnion = new java.util.concurrent.ConcurrentSkipListSet<>();

        // Guards so programmatic combo changes don't trigger applying qualities
        final javafx.beans.property.BooleanProperty updatingGlobalCombo =
                new javafx.beans.property.SimpleBooleanProperty(false);

        // Once the user touches any quality control, we allow mixed-state logic to drive the global combo.
        final java.util.concurrent.atomic.AtomicBoolean userQualityInteracted =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        // Guards so programmatic selection/check changes don't count as "user interaction"
        final javafx.beans.property.BooleanProperty updatingSelection =
                new javafx.beans.property.SimpleBooleanProperty(false);

        // Mixed state rule (matches UX requirement):
        // - Default shows the CURRENT global desired quality (Best by default)
        // - If ANY selected item is manually overridden -> show CUSTOM
        // - When manual overrides are cleared (back to global), global returns to the global desired value
        final Runnable updateGlobalMixedState = () -> {
            if (anyQualityPopupOpen.get() || updatingGlobalCombo.get()) {
                pendingMixedUpdate.set(true);
                return;
            }

            // If user never interacted, keep the global combo stable (Best by default)
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
                    // no manual overrides -> show desired global quality
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
                // If we're in AUDIO mode, never rebuild the global quality list from video heights.
                if (MODE_AUDIO.equals(globalDesiredMode.get())) {
                    return;
                }

                // Preserve selection across rebuilds (especially CUSTOM)
                String prev = globalQualityCombo.getValue();
                if (prev == null || prev.isBlank()) prev = QUALITY_BEST;

                boolean keepCustom = QUALITY_CUSTOM.equals(prev);

                updatingGlobalCombo.set(true);
                try {
                    globalQualityCombo.getItems().clear();

                    if (keepCustom) {
                        globalQualityCombo.getItems().add(QUALITY_CUSTOM);
                    }

                    if (globalHeightsUnion.isEmpty()) {
                        // Safe fallback list
                        globalQualityCombo.getItems().addAll(
                                QUALITY_BEST,
                                QUALITY_SEPARATOR,
                                "1080p",
                                "720p",
                                "480p",
                                "360p",
                                "240p",
                                "144p"
                        );
                    } else {
                        // Only normalized ladder values
                        java.util.Set<Integer> normalized = normalizeHeights(new java.util.HashSet<>(globalHeightsUnion));
                        java.util.List<Integer> sorted = new java.util.ArrayList<>(normalized);
                        sorted.sort(java.util.Comparator.reverseOrder());

                        globalQualityCombo.getItems().add(QUALITY_BEST);
                        globalQualityCombo.getItems().add(QUALITY_SEPARATOR);
                        for (Integer h : sorted) {
                            globalQualityCombo.getItems().add(formatHeightLabel(h));
                        }
                    }

                    // Restore selection without fighting CUSTOM
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

                // If the user already interacted, re-evaluate mixed state once after rebuild.
                Platform.runLater(() -> {
                    Runnable r = updateGlobalMixedStateRef.get();
                    if (r != null) r.run();
                });
            });
            globalComboUpdateThrottle.playFromStart();
        };

        // =============================
        // Global mode listener (put here so items/requestRefreshSafe/updateGlobalMixedState/updateGlobalQualityCombo are in-scope)
        // =============================
        globalModeCombo.valueProperty().addListener((obs, old, val) -> {
            if (val == null) return;

            globalDesiredMode.set(val);

            // Switch the global quality list based on mode (guard to avoid triggering globalQuality listener)
            updatingGlobalCombo.set(true);
            try {
                if (MODE_AUDIO.equals(val)) {
                    globalQualityCombo.getItems().setAll(buildAudioOptions());
                    globalQualityCombo.getSelectionModel().select(AUDIO_DEFAULT_FORMAT);
                    globalDesiredQuality.set(AUDIO_DEFAULT_FORMAT);
                } else {
                    // rebuild video list from union (keeps best default)
                    updateGlobalQualityCombo.run();
                    globalQualityCombo.getSelectionModel().select(QUALITY_BEST);
                    globalDesiredQuality.set(QUALITY_BEST);
                }
            } finally {
                updatingGlobalCombo.set(false);
            }

            // Priority to global: apply to ALL items
            for (PlaylistEntry it : items) {
                if (it == null || it.isUnavailable()) continue;
                it.setManualQuality(false);
                it.setQuality(MODE_AUDIO.equals(val) ? globalDesiredQuality.get() : QUALITY_BEST);
            }

            requestRefreshSafe.run();
            Platform.runLater(updateGlobalMixedState);
            // Now that the user explicitly changed mode, allow mixed-state logic (for quality) later.
            userQualityInteracted.set(true);
        });

        list.setCellFactory(lv -> new ListCell<>() {

            private final CheckBox cb = new CheckBox();
            private final Label title = new Label();
            private final Label meta = new Label();

            private final VBox textBox = new VBox(4);

            private final StackPane thumbBox = new StackPane();
            private final ImageView thumb = new ImageView();
            private final Label placeholder = new Label("NO PREVIEW");

            private final ComboBox<String> qualityCombo = new ComboBox<>();
            private boolean updatingRowCombo = false;

            private final HBox card = new HBox(12);


            {
                setStyle("-fx-background-color: transparent;");

                // Checkbox
                cb.getStyleClass().addAll("gx-check", "gx-playlist-check");
                cb.setFocusTraversable(false);

                // Thumbnail
                thumb.setFitWidth(96);
                thumb.setFitHeight(54);
                thumb.setPreserveRatio(true);
                thumb.setSmooth(true);

                placeholder.getStyleClass().add("gx-playlist-thumb-placeholder");

                thumbBox.getStyleClass().add("gx-playlist-thumb");
                thumbBox.getChildren().addAll(thumb, placeholder);

                // Text
                title.getStyleClass().add("gx-playlist-title");
                title.setWrapText(false);

                meta.getStyleClass().add("gx-playlist-meta");

                textBox.getChildren().addAll(title, meta);
                HBox.setHgrow(textBox, Priority.ALWAYS);

                // Quality Combo (right side)
                qualityCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality");
                qualityCombo.setPrefWidth(115);
                qualityCombo.setMaxWidth(220);
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

                // Prevent refresh from closing the popup while user is choosing a quality
                qualityCombo.showingProperty().addListener((obs, was, isNow) -> anyQualityPopupOpen.set(isNow));

                qualityCombo.valueProperty().addListener((obs, old, val) -> {
                    if (updatingRowCombo) return;
                    if (val == null || QUALITY_SEPARATOR.equals(val)) return;

                    PlaylistEntry it = getItem();
                    if (it == null) return;

                    // USER interaction
                    userQualityInteracted.set(true);

                    // Apply selected value to THIS item only
                    it.setQuality(val);

                    // If chosen value equals what the GLOBAL desired would map to for this item,
                    // then it's not a manual override.
                    String desired = globalDesiredQuality.get();
                    if (desired == null || desired.isBlank()) desired = QUALITY_BEST;

                    String modeNow = globalDesiredMode.get();
                    if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

                    String globalMapped;
                    if (MODE_AUDIO.equals(modeNow)) {
                        // In audio mode, global desired is the selected format (e.g., mp3)
                        globalMapped = desired;
                    } else {
                        java.util.List<String> avail = it.getAvailableQualities();
                        globalMapped = (avail == null || avail.isEmpty())
                                ? desired
                                : pickClosestSupportedQuality(desired, avail);
                    }

                    boolean manual = !val.equals(globalMapped);
                    it.setManualQuality(manual);

                    meta.setText(buildMetaLine(it));
                    Platform.runLater(updateGlobalMixedState);
                });

                // Card
                card.getStyleClass().add("gx-playlist-card");
                card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                card.getChildren().addAll(cb, thumbBox, textBox, qualityCombo);

                // Toggle selection on card click
                card.setOnMouseClicked(e -> {
                    if (isEmpty() || getItem() == null) return;
                    cb.setSelected(!cb.isSelected());
                });

                cb.selectedProperty().addListener((obs, was, isNow) -> {
                    PlaylistEntry it = getItem();
                    if (it == null) return;

                    if (it.isUnavailable()) {
                        // force it off
                        cb.setSelected(false);
                        it.setSelected(false);
                        return;
                    }

                    it.setSelected(isNow);

                    // Only count as a user interaction when it wasn't a programmatic bulk change
                    if (!updatingSelection.get()) {
                        userQualityInteracted.set(true);
                        Platform.runLater(updateGlobalMixedState);
                    }
                });
            }

            @Override
            protected void updateItem(PlaylistEntry item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                title.setText(item.displayTitle());
                meta.setText(buildMetaLine(item));

                cb.setSelected(item.isSelected());

                // Unavailable (private/deleted/etc.)
                if (item.isUnavailable()) {
                    cb.setDisable(true);
                    qualityCombo.setDisable(true);
                    placeholder.setText("UNAVAILABLE");
                    placeholder.setVisible(true);
                    thumb.setImage(null);

                    // keep meta simple and clear
                    meta.setText("Unavailable");

                    // dim the whole card
                    card.setOpacity(0.55);

                    setGraphic(card);
                    return; // IMPORTANT: skip probing
                } else {
                    cb.setDisable(false);
                    card.setOpacity(1.0);
                }

                // Set placeholder text for normal items
                placeholder.setText("NO PREVIEW");

                // ===== AUDIO MODE: show audio formats in each row and skip video probing/quality lists =====
                String modeNow = globalDesiredMode.get();
                if (modeNow == null || modeNow.isBlank()) modeNow = MODE_VIDEO;

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
                        if (!qualityCombo.getItems().contains(cur)) cur = AUDIO_DEFAULT_FORMAT;

                        qualityCombo.getSelectionModel().select(cur);
                    } finally {
                        updatingRowCombo = false;
                    }

                    meta.setText(buildMetaLine(item));
                    setGraphic(card);
                    return;
                }

                // Thumbnail load (cached + inflight guard)
                String tid = item.getId();
                Image cachedThumb = (tid == null) ? null : PLAYLIST_THUMB_CACHE.get(tid);

                if (cachedThumb != null) {
                    thumb.setImage(cachedThumb);
                    placeholder.setVisible(false);

                } else if (item.getThumbUrl() != null && !item.getThumbUrl().isBlank()) {
                    placeholder.setVisible(true);

                    // Create the Image ONCE per video id, cache it immediately so recycled cells reuse it
                    if (tid != null && PLAYLIST_THUMB_INFLIGHT.add(tid)) {

                        Image img = new Image(item.getThumbUrl(), true);
                        PLAYLIST_THUMB_CACHE.put(tid, img);

                        img.progressProperty().addListener((o, oldP, newP) -> {
                            if (newP != null && newP.doubleValue() >= 1.0) {
                                PLAYLIST_THUMB_INFLIGHT.remove(tid);
                                // If this cell is still showing same item, hide placeholder
                                Platform.runLater(() -> {
                                    PlaylistEntry now = getItem();
                                    if (now != null && tid.equals(now.getId()) && img.getException() == null) {
                                        placeholder.setVisible(false);
                                    }
                                });
                            }
                        });

                        img.exceptionProperty().addListener((o, oldEx, ex) -> {
                            PLAYLIST_THUMB_INFLIGHT.remove(tid);
                        });
                    }

                    Image reuse = (tid == null) ? null : PLAYLIST_THUMB_CACHE.get(tid);
                    thumb.setImage(reuse);
                    if (reuse != null && reuse.getProgress() >= 1.0 && reuse.getException() == null) {
                        placeholder.setVisible(false);
                    } else {
                        placeholder.setVisible(true);
                    }

                } else {
                    thumb.setImage(null);
                    placeholder.setVisible(true);
                }


                // If qualities not loaded yet → probe in background once
                if (!item.isQualitiesLoaded()) {
                    item.setQualitiesLoaded(true); // mark immediately to prevent multi-threads from multiple cells
                    qualityCombo.setDisable(true);
                    updatingRowCombo = true;
                    try {
                        fillQualityCombo(qualityCombo);
                        qualityCombo.getSelectionModel().select(item.getQuality());
                    } finally {
                        updatingRowCombo = false;
                    }

                    String videoId = item.getId();
                    String videoUrl = youtubeWatchUrl(videoId);

                    boolean queued = probeVideoQualitiesAsync(videoUrl, videoId, pr -> {

                        // raw heights from probe
                        java.util.Set<Integer> heights = (pr == null) ? java.util.Set.of() : pr.heights;

                        // ✅ normalize + keep only ladder (2160/1440/1080/720/480/360/240/144)
                        java.util.Set<Integer> norm = normalizeHeights(heights);

                        // ✅ update global union from NORMALIZED heights only
                        if (norm != null && !norm.isEmpty()) {
                            globalHeightsUnion.addAll(norm);
                        }
                        Platform.runLater(updateGlobalQualityCombo);

                        // ✅ labels: Best + separator + ONLY normalized heights
                        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
                        labels.add(QUALITY_BEST);
                        labels.add(QUALITY_SEPARATOR);

                        java.util.List<Integer> sorted = (norm == null)
                                ? new java.util.ArrayList<>()
                                : new java.util.ArrayList<>(norm);

                        sorted.sort(java.util.Comparator.reverseOrder());

                        for (Integer h : sorted) {
                            labels.add(formatHeightLabel(h));
                        }
                        item.setAvailableQualities(labels);

                        // ✅ size map: label -> "~xxx MB" (use normalized heights keys)
                        java.util.Map<String, String> sizeMap = new java.util.HashMap<>();
                        if (pr != null && pr.sizeByHeight != null) {
                            for (Integer h : sorted) {
                                String label = formatHeightLabel(h);
                                String sz = pr.sizeByHeight.get(h);
                                if (sz != null && !sz.isBlank()) {
                                    sizeMap.put(label, sz);
                                }
                            }
                        }
                        item.setSizeByQuality(sizeMap);

                        // ✅ pick desired + apply ONLY in VIDEO mode (do not overwrite AUDIO format selection)
                        if (!MODE_AUDIO.equals(globalDesiredMode.get())) {
                            String desired = item.getQuality();
                            if (!item.isManualQuality()) {
                                desired = globalDesiredQuality.get();
                                if (desired == null || desired.isBlank()) desired = QUALITY_BEST;
                            }

                            String supported = pickClosestSupportedQuality(desired, item.getAvailableQualities());
                            item.setQuality(supported);
                        }
                        // Platform.runLater(updateGlobalMixedState);

                        // ✅ refresh without closing popups
                        requestRefreshSafe.run();
                    });

                    if (!queued) {
                        // queue full -> allow retry later (VERY IMPORTANT)
                        item.setQualitiesLoaded(false);
                    }

                } else {
                    // already probed (or attempted). Always render from the item's cached qualities.
                    java.util.List<String> q = item.getAvailableQualities();

                    if (q != null && q.size() >= 2) { // Best + separator at minimum
                        updatingRowCombo = true;
                        try {
                            qualityCombo.getItems().setAll(q);
                            qualityCombo.setDisable(false);

                            String cur = item.getQuality();
                            if (cur == null || cur.isBlank()) cur = QUALITY_BEST;

                            if (!qualityCombo.getItems().contains(cur)) {
                                String mapped = pickClosestSupportedQuality(cur, q);
                                item.setQuality(mapped);
                                cur = mapped;
                            }
                            qualityCombo.getSelectionModel().select(cur);
                        } finally {
                            updatingRowCombo = false;
                        }

                    } else {
                        // Probe failed/rejected -> allow retry later instead of getting stuck disabled forever
                        item.setQualitiesLoaded(false);

                        updatingRowCombo = true;
                        try {
                            qualityCombo.getItems().setAll(QUALITY_BEST, QUALITY_SEPARATOR);
                            qualityCombo.getSelectionModel().select(QUALITY_BEST);
                            qualityCombo.setDisable(true);
                        } finally {
                            updatingRowCombo = false;
                        }
                    }
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

        Button addStart = new Button("Add & Start");
        addStart.getStyleClass().addAll("gx-btn", "gx-btn-primary");
        addStart.setDisable(true);

        actions.getChildren().addAll(selectAll, clearSel, spacer, cancel, addStart);

//        rootBox.getChildren().addAll(header, sub, list, status, actions);
        rootBox.getChildren().addAll(header, sub, globalRow, list, status, actions);

        Scene scene = new Scene(rootBox, 820, 560);

        // ✅ مهم جداً: يلوّن خلفية الـ Stage نفسها
        scene.setFill(Color.web("#121826"));
        scene.getRoot().setStyle("-fx-background-color: #121826;");

        // Apply same app styles
        scene.getStylesheets().addAll(
                getClass().getResource("/com/grabx/app/grabx/styles/theme-base.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/layout.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/buttons.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/sidebar.css").toExternalForm()
        );


        // ✅ خلي CSS يتطبق قبل ما تبان النافذة (تقليل الوميض)
        rootBox.applyCss();
        rootBox.layout();

        stage.setScene(scene);


        // Background prefetch a limited number of items so sizes/qualities appear without requiring scroll-to-touch.
        java.util.function.Consumer<PlaylistEntry> ensureProbed = (PlaylistEntry it) -> {
            if (it == null) return;
            if (it.isUnavailable()) return;
            if (it.isQualitiesLoaded()) return;

            it.setQualitiesLoaded(true);

            String videoId = it.getId();
            String videoUrl = youtubeWatchUrl(videoId);

            boolean queued = probeVideoQualitiesAsync(videoUrl, videoId, pr -> {
                java.util.Set<Integer> heights = (pr == null) ? java.util.Set.of() : pr.heights;

                java.util.Set<Integer> norm = normalizeHeights(heights);
                if (norm != null && !norm.isEmpty()) {
                    globalHeightsUnion.addAll(norm);
                }
                Platform.runLater(updateGlobalQualityCombo);

                // labels: Best + separator + ONLY actual heights
                java.util.ArrayList<String> labels = new java.util.ArrayList<>();
                labels.add(QUALITY_BEST);
                labels.add(QUALITY_SEPARATOR);

                java.util.Set<Integer> norm2 = normalizeHeights(heights);
                java.util.List<Integer> sorted = new java.util.ArrayList<>(norm2);
                sorted.sort(java.util.Comparator.reverseOrder());

                for (Integer h : sorted) labels.add(formatHeightLabel(h));
                it.setAvailableQualities(labels);

                // size map: label -> "~xxx MB"
                java.util.Map<String, String> sizeMap = new java.util.HashMap<>();
                if (pr != null && pr.sizeByHeight != null) {
                    for (Integer h : sorted) {
                        String label = formatHeightLabel(h);
                        String sz = pr.sizeByHeight.get(h);
                        if (sz != null && !sz.isBlank()) sizeMap.put(label, sz);
                    }
                }
                it.setSizeByQuality(sizeMap);

                // pick desired + apply ONLY in VIDEO mode (do not overwrite AUDIO format selection)
                if (!MODE_AUDIO.equals(globalDesiredMode.get())) {
                    String desired = it.getQuality();
                    if (!it.isManualQuality()) {
                        desired = globalDesiredQuality.get();
                        if (desired == null || desired.isBlank()) desired = QUALITY_BEST;
                    }

                    String supported = pickClosestSupportedQuality(desired, it.getAvailableQualities());
                    it.setQuality(supported);
                }
                // Platform.runLater(updateGlobalMixedState); // REMOVE: do not update mixed state during probing

                requestRefreshSafe.run();
            });

            if (!queued) {
                // queue full -> allow retry later
                it.setQualitiesLoaded(false);
            }
        };

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
                    it.setQuality(val); // format
                } else {
                    java.util.List<String> avail = it.getAvailableQualities();
                    String mapped = (avail == null || avail.isEmpty())
                            ? val
                            : pickClosestSupportedQuality(val, avail);
                    it.setQuality(mapped);
                }
            }

            requestRefreshSafe.run();
            Platform.runLater(updateGlobalMixedState);
        });

        Runnable refreshAddState = () -> {
            boolean any = items.stream().anyMatch(PlaylistEntry::isSelected);
            addStart.setDisable(!any);
        };

        selectAll.setOnAction(e -> {
            for (PlaylistEntry it : items) it.setSelected(true);
            requestRefreshSafe.run();
            refreshAddState.run();
        });

        clearSel.setOnAction(e -> {
            for (PlaylistEntry it : items) it.setSelected(false);
            requestRefreshSafe.run();
            refreshAddState.run();
        });

        cancel.setOnAction(e -> stage.close());

        addStart.setOnAction(e -> {
            int count = (int) items.stream().filter(PlaylistEntry::isSelected).count();
            if (statusText != null) {
                statusText.setText("Start playlist: " + count + " items | " + shorten(playlistUrl));
            }
            stage.close();
        });

        // Load playlist entries asynchronously
        new Thread(() -> {
            java.util.List<PlaylistEntry> loaded = probePlaylistFlat(playlistUrl);
            Platform.runLater(() -> {
                items.setAll(loaded);
                if (loaded.isEmpty()) {
                    status.setText("Could not load playlist (yt-dlp missing?)");
                } else {
                    long bad = loaded.stream().filter(PlaylistEntry::isUnavailable).count();
                    status.setText("Loaded " + loaded.size() + " items" + (bad > 0 ? (" • " + bad + " unavailable") : ""));
                }

                // default select all (skip unavailable) - programmatic, do NOT treat as user interaction
                updatingSelection.set(true);
                try {
                    for (PlaylistEntry it : items) {
                        it.setSelected(!it.isUnavailable());
                    }
                } finally {
                    updatingSelection.set(false);
                }

                // initial default: BEST (do not mark as user interaction)
                updatingGlobalCombo.set(true);
                try {
                    globalQualityCombo.getSelectionModel().select(QUALITY_BEST);
                } finally {
                    updatingGlobalCombo.set(false);
                }
                globalDesiredQuality.set(QUALITY_BEST);

                requestRefreshSafe.run();
                refreshAddState.run();
                // Prefetch progressively so sizes appear without needing scroll-to-touch
                new Thread(() -> {
                    try {
                        // Fast first batch
                        int firstBatch = Math.min(30, items.size());
                        for (int i = 0; i < firstBatch && !stopPrefetch.get(); i++) {
                            ensureProbed.accept(items.get(i));
                            Thread.sleep(25);
                        }

                        // Continue gently (prefer selected)
                        int idx = 0;
                        while (!stopPrefetch.get() && idx < items.size()) {
                            PlaylistEntry it = items.get(idx++);
                            if (it != null && it.isSelected() && !it.isUnavailable()) {
                                ensureProbed.accept(it);
                            }
                            Thread.sleep(60);
                        }
                    } catch (InterruptedException ignored) {
                    }
                }, "playlist-prefetch").start();
            });
        }, "probe-playlist").start();

        stage.showAndWait();
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


    private static java.util.List<PlaylistEntry> probePlaylistFlat(String playlistUrl) {
        java.util.List<PlaylistEntry> out = new java.util.ArrayList<>();
        if (playlistUrl == null || playlistUrl.isBlank()) return out;

        try {
            // Flat playlist to avoid heavy metadata; print: ID|TITLE
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--flat-playlist",
                    "--no-warnings",
                    "--print",
                    "%(id)s|%(title)s",
                    playlistUrl
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int idx = line.indexOf('|');
                    String id = idx >= 0 ? line.substring(0, idx).trim() : line;
                    if (id.isBlank()) continue;
                    String title = idx >= 0 ? line.substring(idx + 1).trim() : "";
                    int index = out.size() + 1;

                    PlaylistEntry entry = new PlaylistEntry(index, id, title, youtubeThumbUrl(id), true);

                    // yt-dlp flat playlist returns these special titles for unavailable items
                    String t = (title == null) ? "" : title.trim();
                    boolean unavailable = t.equalsIgnoreCase("[Private video]")
                            || t.equalsIgnoreCase("[Deleted video]")
                            || t.toLowerCase().contains("private video")
                            || t.toLowerCase().contains("deleted video");

                    if (unavailable) {
                        entry.setUnavailable(true);
                        entry.setUnavailableReason(t);
                        entry.setSelected(false); // do not auto-select
                    }

                    out.add(entry);
                }
            }

            p.waitFor();
        } catch (Exception ignored) {
        }

        return out;
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

    private void setupClipboardAutoPaste() {
        if (root == null) return;

        // Only run once
        if (Boolean.TRUE.equals(root.getProperties().get("gx-clip-listener"))) return;
        root.getProperties().put("gx-clip-listener", Boolean.TRUE);

        // Window focus: fire when app is focused (to catch when user returns from copying a URL)
        root.sceneProperty().addListener((o1, oldScene, newScene) -> {
            if (newScene == null) return;
            newScene.windowProperty().addListener((o2, oldW, newW) -> {
                if (newW == null) return;
                newW.focusedProperty().addListener((o3, was, isNow) -> {
                    if (!isNow) return;

                    String clip = readClipboardTextSafe();
                    if (addLinkDialogOpen && activeAddLinkUrlField != null && clip.equals(activeAddLinkUrlField.getText())) {
                        return;
                    }
                    if (clip.equals(lastClipboardText)) return;
                    lastClipboardText = clip;

                    if (!isHttpUrl(clip)) return;

                    // If dialog is open -> update field and focus it.
                    if (addLinkDialogOpen) {
                        pendingAddLinkPrefillUrl = clip;
                        if (activeAddLinkUrlField != null) {
                            activeAddLinkUrlField.setText(clip);
                            activeAddLinkUrlField.positionCaret(activeAddLinkUrlField.getText().length());
                            Platform.runLater(activeAddLinkUrlField::requestFocus);
                        }
                        return;
                    }

                    // If dialog is NOT open -> auto-open it on app focus with the new clipboard URL.
                    openOrUpdateAddLinkDialog(clip);
                });
            });

        });

        // One-time startup: if clipboard already has a URL when the window is first shown, open Add Link (slight delay)
        if (root.getProperties().get("gx-clip-startup") == null) {
            root.getProperties().put("gx-clip-startup", Boolean.TRUE);

            root.sceneProperty().addListener((sx, oldS, newS) -> {
                if (newS == null) return;
                newS.windowProperty().addListener((wx, oldW, newW) -> {
                    if (newW == null) return;

                    newW.showingProperty().addListener((shx, wasShowing, isShowing) -> {
                        if (!isShowing) return;
                        if (Boolean.TRUE.equals(newW.getProperties().get("gx-clip-startup-done"))) return;
                        newW.getProperties().put("gx-clip-startup-done", Boolean.TRUE);

                        String clip = readClipboardTextSafe();
                        lastClipboardText = clip;
                        if (!isHttpUrl(clip)) return;

                        // slight delay so main UI finishes layout before show
                        PauseTransition pt = new PauseTransition(Duration.millis(260));
                        pt.setOnFinished(e -> openOrUpdateAddLinkDialog(clip));
                        pt.playFromStart();
                    });
                });
            });
        }

        // Timeline polling: keep this for live update if dialog is open, and for auto-open while app is focused
        try {
            if (clipboardPollTimeline != null) {
                clipboardPollTimeline.stop();
            }
        } catch (Exception ignored) {}

        clipboardPollTimeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.millis(900), ev -> {
                    String clip = readClipboardTextSafe();
                    if (clip.equals(lastClipboardText)) return;
                    lastClipboardText = clip;

                    // If Add Link dialog is open -> live update its URL field ONLY when clipboard is a URL.
                    if (addLinkDialogOpen) {
                        if (isHttpUrl(clip)) {
                            pendingAddLinkPrefillUrl = clip;
                            if (activeAddLinkUrlField != null) {
                                activeAddLinkUrlField.setText(clip);
                                activeAddLinkUrlField.positionCaret(activeAddLinkUrlField.getText().length());
                            }
                        }
                        return;
                    }

                    // If dialog is NOT open and the main window is currently focused,
                    // auto-open Add Link dialog when user copies a NEW URL while app is in foreground.
                    if (isHttpUrl(clip)) {
                        try {
                            var sc = root.getScene();
                            var w = (sc == null) ? null : sc.getWindow();
                            boolean focused = (w != null) && w.isFocused();
                            if (focused) {
                                openOrUpdateAddLinkDialog(clip);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                })
        );
        clipboardPollTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        clipboardPollTimeline.play();
    }



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

}