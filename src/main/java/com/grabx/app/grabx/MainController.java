package com.grabx.app.grabx;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.ui.components.HoverBubble;
import com.grabx.app.grabx.ui.components.NoSelectionModel;
import com.grabx.app.grabx.ui.probe.ProbeQualitiesResult;
import javafx.scene.layout.HBox;
import javafx.collections.transformation.FilteredList;

import java.awt.Desktop;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;

import java.nio.file.Path;
import java.nio.file.Paths;

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


    // ========= Playlist probing (IMPORTANT: limit concurrency to avoid freezing on large playlists) =========
    private static final int PLAYLIST_PROBE_THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
    private final java.util.Map<String, Long> sizeCache = new java.util.concurrent.ConcurrentHashMap<>();

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
//            new ThreadPoolExecutor.DiscardPolicy()
            new ThreadPoolExecutor.AbortPolicy()
    );

    private static final Map<String, ProbeQualitiesResult> PLAYLIST_PROBE_CACHE = new ConcurrentHashMap<>();
    // Cache thumbnails to avoid re-downloading when cells are recycled
    private static final Map<String, Image> PLAYLIST_THUMB_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PLAYLIST_PROBE_INFLIGHT = ConcurrentHashMap.newKeySet();
    // Avoid creating multiple Image downloads for the same thumbnail when cells are recycled
    private static final Set<String> PLAYLIST_THUMB_INFLIGHT = ConcurrentHashMap.newKeySet();

    private final java.util.Map<DownloadRow, Process> activeProcesses = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<DownloadRow, String> stopReasons = new java.util.concurrent.ConcurrentHashMap<>();
//    private static final String YTDLP_OUT_TMPL = "%(title).200B [%(id)s].%(ext)s";
    private static final String YTDLP_OUT_TMPL = "%(title)s.%(ext)s";

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



    private final ObservableList<DownloadRow> downloadItems = FXCollections.observableArrayList();
    private FilteredList<DownloadRow> filteredDownloadItems;

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

    private static ProbeQualitiesResult probeQualitiesWithSizes(String url) {
        long now = System.currentTimeMillis();

        // Cache hit (per URL)
        try {
            ProbeQualitiesResult cached = VIDEO_INFO_CACHE.get(url);
            if (cached != null && cached.isFresh()) return cached;
        } catch (Exception ignored) {}

        java.util.Set<Integer> heights = new java.util.HashSet<>();
        java.util.Map<Integer, String> sizeByHeight = new java.util.HashMap<>();
        java.util.Map<Integer, Long> bytesByHeight = new java.util.HashMap<>();

        if (url == null || url.isBlank()) {
            return new ProbeQualitiesResult(heights, bytesByHeight, sizeByHeight, -1L, now);
        }

        // 1) detect heights once (FAST)
        heights = probeHeightsWithYtDlp(url);

        // 2) compute exact bytes ONLY for the highest detected height (FAST enough for Get)
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

                    // seed SIZE_CACHE for Best and for best height label
                    SIZE_CACHE.put(url + "|" + MODE_VIDEO + "|" + QUALITY_BEST, b);
                    SIZE_CACHE.put(url + "|" + MODE_VIDEO + "|" + formatHeightLabel(bestH), b);
                }
            } catch (Exception ignored) {}
        }

        // 3) schedule background exact-size probes for remaining heights (NON-BLOCKING)
        for (Integer h : heights) {
            if (h == null || h <= 0) continue;
            if (bestH != null && h.intValue() == bestH.intValue()) continue;

            String label = formatHeightLabel(h);
            String cacheKey = url + "|" + MODE_VIDEO + "|" + label;
            if (SIZE_CACHE.containsKey(cacheKey)) continue;

            String inflightKey = url + "||" + label;
            if (!VIDEO_SIZE_INFLIGHT.add(inflightKey)) continue;

            final int fh = h;
            VIDEO_SIZE_EXEC.execute(() -> {
                try {
                    String selector = buildFormatSelectorForHeight(fh);
                    Long b = fetchCombinedSizeBytesWithYtDlpPrint(url, selector);
                    if (b != null && b > 0) {
                        SIZE_CACHE.put(cacheKey, b);
                    }
                } catch (Exception ignored) {
                } finally {
                    VIDEO_SIZE_INFLIGHT.remove(inflightKey);
                }
            });
        }

        heights = normalizeHeights(heights);
        sizeByHeight.keySet().retainAll(heights);
        bytesByHeight.keySet().retainAll(heights);

        ProbeQualitiesResult pr = new ProbeQualitiesResult(heights, bytesByHeight, sizeByHeight, bestBytes, now);
        try { VIDEO_INFO_CACHE.put(url, pr); } catch (Exception ignored) {}
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
        applyFilter("ALL");

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
    private void applyFilter(String key) {
        if (filteredDownloadItems == null) return;

        String k = (key == null) ? "ALL" : key.trim().toUpperCase(java.util.Locale.ROOT);

        filteredDownloadItems.setPredicate(row -> {
            if (row == null) return false;

            DownloadRow.State st = null;
            try { st = row.state.get(); } catch (Exception ignored) {}

            if ("ALL".equals(k)) return true;

            if ("DOWNLOADING".equals(k)) return st == DownloadRow.State.DOWNLOADING;
            if ("PAUSED".equals(k))      return st == DownloadRow.State.PAUSED;
            if ("COMPLETED".equals(k))   return st == DownloadRow.State.COMPLETED;

            // بدك دمج Cancelled + Failed مع بعض (زي ما اتفقنا)
            if ("CANCELLED".equals(k))   return st == DownloadRow.State.CANCELLED || st == DownloadRow.State.FAILED;

            return true;
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

    // Persist last selected download folder
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainController.class);
    private static final String PREF_LAST_FOLDER = "last_download_folder";

    private String getLastDownloadFolderOrDefault() {
        try {
            String v = PREFS.get(PREF_LAST_FOLDER, null);
            if (v != null && !v.isBlank()) {
                java.nio.file.Path p = java.nio.file.Paths.get(v);
                if (java.nio.file.Files.exists(p) && java.nio.file.Files.isDirectory(p)) {
                    return p.toAbsolutePath().toString();
                }
            }
        } catch (Exception ignored) {}
        return System.getProperty("user.home") + java.io.File.separator + "Downloads";
    }

    private void saveLastDownloadFolder(String folder) {
        try {
            if (folder == null) return;
            String v = folder.trim();
            if (v.isEmpty()) return;

            java.nio.file.Path p = java.nio.file.Paths.get(v);

            // لو موجود وملف (مش فولدر) لا تحفظه
            if (java.nio.file.Files.exists(p) && !java.nio.file.Files.isDirectory(p)) return;

            PREFS.put(PREF_LAST_FOLDER, p.toAbsolutePath().toString());
        } catch (Exception ignored) {}
    }

    private void showAddLinkDialog() {
        showAddLinkDialog(null);
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
//            new ThreadPoolExecutor.DiscardPolicy()
            new ThreadPoolExecutor.AbortPolicy()
    );

    // Avoid duplicate probes for the same (url|quality) while one is already running
    private static final java.util.Set<String> VIDEO_SIZE_INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();


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

        // Track last probed video heights so we can restore the quality list when switching modes.
        final java.util.Set<Integer>[] lastProbedHeights = new java.util.Set[]{null};
        // Store probed size TEXT per quality label (video) so switching is instant (no yt-dlp calls on selection change)
        final java.util.Map<String, String>[] lastProbedSizeTextByQualityLabel =
                new java.util.Map[]{new java.util.HashMap<>()};

        // Folder
//        TextField folderField = new TextField(System.getProperty("user.home") + File.separator + "Downloads");
        TextField folderField = new TextField(getLastDownloadFolderOrDefault());

        folderField.setEditable(false);
        folderField.getStyleClass().add("gx-input");

        Button browseBtn = new Button("Browse");
        browseBtn.getStyleClass().addAll("gx-btn", "gx-btn-ghost");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Download Folder");
            File selected = chooser.showDialog(pane.getScene().getWindow());
            if (selected != null) {
                folderField.setText(selected.getAbsolutePath());
                saveLastDownloadFolder(selected.getAbsolutePath());
            }
        });

        // Info / status line inside dialog
        Label info = new Label("Paste a link then click Get.");
        info.getStyleClass().add("gx-text-muted");
        info.setWrapText(true);
        // Keep a consistent default color; errors will override temporarily
        info.setTextFill(Color.web("#9aa4b2"));

        Label sizeInfo = new Label("Estimated size: —");
        sizeInfo.getStyleClass().add("gx-text-muted");
        sizeInfo.setWrapText(true);
        sizeInfo.setTextFill(Color.web("#9aa4b2"));

        // Size loading animation (dots) + request token to ignore late results
        final long[] sizeReqId = {0};
        final int[] sizeDots = {0};

        javafx.animation.Timeline sizeLoadingTl = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(280), ev2 -> {
                    sizeDots[0] = (sizeDots[0] % 3) + 1; // 1..3
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

        java.util.function.Consumer<String> setSizeText = (txt) -> {
            stopSizeLoading.run();
            sizeInfo.setText(txt);
        };

        Runnable startSizeLoading = () -> {
            stopSizeLoading.run();
            sizeInfo.setText("Estimating: .");
            try { sizeLoadingTl.playFromStart(); } catch (Exception ignored) {}
        };

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

        r++;
        grid.add(sizeInfo, 1, r, 2, 1);

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

        // Outer updateSizeAsync runnable (for use in listeners)
        Runnable updateSizeAsync = () -> {
            String u = urlField.getText() == null ? "" : urlField.getText().trim();
            if (u.isBlank()) {
                setSizeText.accept("Estimated size: —");
                return;
            }

            // Build a stable cache key (include mode + quality)
            String modeV = modeCombo.getValue() == null ? "" : modeCombo.getValue();
            String qV = qualityCombo.getValue() == null ? "" : qualityCombo.getValue();
            String key = u + "|" + modeV + "|" + qV;

            // If cached -> show immediately and stop (no probing)
            Long cached = SIZE_CACHE.get(key);
            if (cached != null && cached > 0) {
                setSizeText.accept("Estimated size: " + formatBytesDecimal(cached));
                return;
            }

            setSizeText.accept("Estimated size: — ");

            // New request id so late background results won't override newer selections
            final long rid = ++sizeReqId[0];

            if (lastType[0] == ContentType.DIRECT_FILE) {
                startSizeLoading.run();
                new Thread(() -> {
                    Long bytes = probeContentLength(u);
                    if (bytes != null && bytes > 0) SIZE_CACHE.put(key, bytes);
                    Platform.runLater(() -> {
                        if (rid != sizeReqId[0]) return;
                        if (bytes != null && bytes > 0) setSizeText.accept("Estimated size: " + formatBytesDecimal(bytes));
                        else setSizeText.accept("Estimated size: — ");
                    });
                }, "probe-size-head").start();
                return;
            }

            if (lastType[0] == ContentType.VIDEO) {
                // Ignore separator selection
                if (QUALITY_SEPARATOR.equals(qV)) {
                    setSizeText.accept("Estimated size: — ");
                    return;
                }

                // ✅ Instant: if we probed size text on Get, show immediately (no yt-dlp call here)
                if (MODE_VIDEO.equals(modeV)) {
                    String qLabel = (qV == null || qV.isBlank()) ? QUALITY_BEST : qV;
                    String txt = lastProbedSizeTextByQualityLabel[0].get(qLabel);
                    if (txt != null && !txt.isBlank()) {
                        String t = txt.trim();
                        setSizeText.accept("Estimated size: " + txt.trim());
                        return;
                    }
                }

                startSizeLoading.run();

                // Compute EXACT size for the currently selected quality (single yt-dlp call),
                // and cache it so future switches are instant.
                final String qLabel = (qV == null || qV.isBlank()) ? QUALITY_BEST : qV;
                final String inflightKey = u + "||" + modeV + "||" + qLabel;

                if (!VIDEO_SIZE_INFLIGHT.add(inflightKey)) return; // already running

                try {
                    VIDEO_SIZE_EXEC.execute(() -> {
                        Long bytes = null;
                        try {
                            if (MODE_VIDEO.equals(modeV)) {
                                if (QUALITY_BEST.equals(qLabel)) {
                                    bytes = fetchCombinedSizeBytesWithYtDlpPrint(u, "bv*+ba/b");
                                } else {
                                    int h = parseHeightFromLabel(qLabel);
                                    String selector = (h > 0) ? buildFormatSelectorForHeight(h) : "bv*+ba/b";
                                    bytes = fetchCombinedSizeBytesWithYtDlpPrint(u, selector);
                                }
                            } else {
                                // Audio mode keeps existing behavior
                                bytes = fetchSizeWithYtDlp(u, modeV, qV);
                            }

                            if (bytes != null && bytes > 0) {
                                SIZE_CACHE.put(key, bytes);
                            }
                        } catch (Exception ignored) {
                        } finally {
                            VIDEO_SIZE_INFLIGHT.remove(inflightKey);
                        }

                        final Long fbytes = bytes;
                        Platform.runLater(() -> {
                            if (rid != sizeReqId[0]) return;
                            if (fbytes != null && fbytes > 0) setSizeText.accept("Estimated size: " + formatBytesDecimal(fbytes));
                            else setSizeText.accept("Estimated size: —");
                        });
                    });
                } catch (RejectedExecutionException rex) {
                    // Pool is full; cannot probe now
                    VIDEO_SIZE_INFLIGHT.remove(inflightKey);
                    Platform.runLater(() -> {
                        if (rid != sizeReqId[0]) return;
                        setSizeText.accept("Estimated size: —");
                    });
                }
                return;

            }

            // PLAYLIST / UNSUPPORTED
            setSizeText.accept("Estimated size: —");
        };

        // When mode changes, swap the Quality dropdown contents accordingly.
        modeCombo.valueProperty().addListener((obsM, oldM, newM) -> {

            if (newM == null) return;

            if (MODE_AUDIO.equals(newM)) {
                qualityCombo.getItems().setAll(buildAudioOptions());
                // Default audio format in UI
                qualityCombo.getSelectionModel().select(AUDIO_DEFAULT_FORMAT);
            } else {
                // Restore video qualities (prefer the ones we probed; otherwise safe fallback)
                fillQualityComboFromHeights(qualityCombo, lastProbedHeights[0]);
                qualityCombo.getSelectionModel().select(QUALITY_BEST);
            }

            // If Get already succeeded for a video, refresh the estimated size
            if (!okBtn.isDisabled() && lastType[0] == ContentType.VIDEO) {
                updateSizeAsync.run();
            }
        });

        qualityCombo.valueProperty().addListener((obsQ, oldQ, newQ) -> {
            if (okBtn.isDisabled()) return;            // فقط بعد ما Get ينجح
            if (lastType[0] != ContentType.VIDEO) return;
            if (newQ == null) return;
            if (QUALITY_SEPARATOR.equals(newQ)) return;
            updateSizeAsync.run();
        });

        Runnable applyTypeToUi = () -> {
            ContentType t = lastType[0];

            if (t == ContentType.VIDEO) {
                modeCombo.setDisable(false);
                qualityCombo.setDisable(false);
                // Ensure Quality list matches current Mode
                if (MODE_AUDIO.equals(modeCombo.getValue())) {
                    qualityCombo.getItems().setAll(buildAudioOptions());
                    if (qualityCombo.getValue() == null || qualityCombo.getValue().isBlank()
                            || QUALITY_SEPARATOR.equals(qualityCombo.getValue())
                            || QUALITY_BEST.equals(qualityCombo.getValue())) {
                        qualityCombo.getSelectionModel().select(AUDIO_DEFAULT_FORMAT);
                    }
                }
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
                // Empty URL -> guide the user and STOP (do not probe)
                lastType[0] = ContentType.UNSUPPORTED;

                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);

                // Reset quality list based on current mode
                if (MODE_AUDIO.equals(modeCombo.getValue())) {
                    qualityCombo.getItems().setAll(buildAudioOptions());
                    qualityCombo.getSelectionModel().select(AUDIO_DEFAULT_FORMAT);
                } else {
                    fillQualityCombo(qualityCombo);
                }

                info.setText("Paste a link then click Get.");
                info.setTextFill(Color.web("#ff4d4d"));
                okBtn.setDisable(true);
                setSizeText.accept("Estimated size: —");
                return;
            }

            lastType[0] = analyzeUrlType(url);

            // If it's a single video, probe available heights and rebuild the quality list accordingly
            if (lastType[0] == ContentType.VIDEO) {
                info.setText("Analyzing formats...");
                info.setTextFill(Color.web("#9aa4b2"));

                new Thread(() -> {
                    ProbeQualitiesResult pr = probeQualitiesWithSizes(url);

                    final java.util.Set<Integer> heights = (pr == null || pr.heights == null) ? java.util.Set.of() : pr.heights;
                    final java.util.Map<Integer, Long> bytesByHeight = (pr == null || pr.bytesByHeight == null) ? java.util.Map.of() : pr.bytesByHeight;

                    Platform.runLater(() -> {
                        // keep for later (when user switches back to Video)
                        lastProbedHeights[0] = heights;
                        lastProbedSizeTextByQualityLabel[0].clear();

                        // Seed SIZE_CACHE so switching qualities is instant (no more yt-dlp calls)
                        if (bytesByHeight != null && !bytesByHeight.isEmpty()) {

                            // best = highest detected height
                            Integer bestH = null;
                            for (Integer h : heights) {
                                if (h == null || h <= 0) continue;
                                if (bestH == null || h > bestH) bestH = h;
                            }

                            if (bestH != null) {
                                Long b = bytesByHeight.get(bestH);
                                if (b != null && b > 0) {
                                    SIZE_CACHE.put(url + "|" + MODE_VIDEO + "|" + QUALITY_BEST, b);
                                    lastProbedSizeTextByQualityLabel[0].put(QUALITY_BEST, formatBytesDecimal(b));
                                }
                            }

                            // per-height entries (1080p/720p/...) using the same label generator used in the UI
                            for (var en : bytesByHeight.entrySet()) {
                                Integer h = en.getKey();
                                Long b = en.getValue();
                                if (h == null || h <= 0 || b == null || b <= 0) continue;

                                String label = formatHeightLabel(h);
                                SIZE_CACHE.put(url + "|" + MODE_VIDEO + "|" + label, b);
                                lastProbedSizeTextByQualityLabel[0].put(label, formatBytesDecimal(b));
                            }
                        }

                        // If user is currently in Audio mode, keep audio list.
                        if (MODE_AUDIO.equals(modeCombo.getValue())) {
                            qualityCombo.getItems().setAll(buildAudioOptions());
                            if (qualityCombo.getValue() == null || qualityCombo.getValue().isBlank()
                                    || QUALITY_SEPARATOR.equals(qualityCombo.getValue())
                                    || QUALITY_BEST.equals(qualityCombo.getValue())) {
                                qualityCombo.getSelectionModel().select(AUDIO_DEFAULT_FORMAT);
                            }
                        } else {
                            // show only the heights that actually exist; fallback if empty
                            fillQualityComboFromHeights(qualityCombo, heights);
                        }

                        applyTypeToUi.run();
                        updateSizeAsync.run();
                    });
                }, "probe-qualities").start();

                return; // UI will update after probing
            }

            // ✅ Playlist: open playlist screen immediately
            if (lastType[0] == ContentType.PLAYLIST) {
                applyTypeToUi.run();
                updateSizeAsync.run();
                saveLastDownloadFolder(folderField.getText());
                openPlaylistWindow(url, folderField.getText());
                return;
            }

            applyTypeToUi.run();
            updateSizeAsync.run();
        });

        // UX: pressing Enter in URL triggers Get
        urlField.setOnAction(e -> getBtn.fire());

        // If user edits URL after Get, require Get again
        urlField.textProperty().addListener((obs, oldV, newV) -> {
            lastType[0] = ContentType.UNSUPPORTED;
            lastProbedSizeTextByQualityLabel[0].clear();
            okBtn.setDisable(true);
            modeCombo.setDisable(true);
            qualityCombo.setDisable(true);
            // Reset quality list according to the currently selected mode
            if (MODE_AUDIO.equals(modeCombo.getValue())) {
                qualityCombo.getItems().setAll(buildAudioOptions());
                qualityCombo.getSelectionModel().select(AUDIO_DEFAULT_FORMAT);
            } else {
                fillQualityCombo(qualityCombo);
            }
            info.setText("Paste a link then click Get.");
            info.setTextFill(Color.web("#9aa4b2"));
            setSizeText.accept("Estimated size: —");
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
            saveLastDownloadFolder(folderField.getText());

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

    // Helper: parse yt-dlp human-readable size token (e.g. 77.59MiB) to bytes
    private static long parseHumanSizeToBytes(String tok) {
        if (tok == null) return -1;
        String s = tok.trim();
        if (s.isEmpty()) return -1;

        // yt-dlp tokens are typically like: 77.59MiB, 1.06GiB, 563.11MiB, 12.3KiB
        double num;
        String unit;
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isDigit(c) || c == '.')) { cut = i; break; }
        }
        if (cut <= 0) return -1;
        try { num = Double.parseDouble(s.substring(0, cut)); } catch (Exception e) { return -1; }
        unit = s.substring(cut).trim();

        long mul;
        String u = unit.toLowerCase(java.util.Locale.ROOT);
        if (u.startsWith("kib")) mul = 1024L;
        else if (u.startsWith("mib")) mul = 1024L * 1024L;
        else if (u.startsWith("gib")) mul = 1024L * 1024L * 1024L;
        else if (u.startsWith("tib")) mul = 1024L * 1024L * 1024L * 1024L;
        else if (u.startsWith("kb")) mul = 1000L;
        else if (u.startsWith("mb")) mul = 1000L * 1000L;
        else if (u.startsWith("gb")) mul = 1000L * 1000L * 1000L;
        else if (u.startsWith("b")) mul = 1L;
        else return -1;

        return (long) Math.max(0, num * mul);
    }

    // --- Size probing helpers ---
    // ========= Start Download Row (single video/file) =========

    // Starts a download for a single DownloadRow (not playlist).

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

//    private Long fetchSizeWithYtDlp(String url, String mode, String quality) {
//        if (url == null || url.isBlank()) return null;
//        try {
//            java.nio.file.Path yt = com.grabx.app.grabx.util.YtDlpManager.ensureAvailable();
//            if (yt == null) return null;
//
//            boolean audioOnly = MODE_AUDIO.equals(mode) || "Audio".equalsIgnoreCase(mode) || "Audio only".equalsIgnoreCase(mode);
//
//            String selector;
//            if (audioOnly) {
//                selector = "bestaudio/best";
//            } else {
//                String q = (quality == null) ? QUALITY_BEST : quality;
//                if (q == null || q.isBlank() || QUALITY_SEPARATOR.equals(q) || QUALITY_BEST.equals(q)) {
//                    selector = "bv*+ba/best";
//                } else {
//                    int h = parseHeightFromLabel(q);
//                    selector = (h > 0)
//                            ? ("bv*[height<=" + h + "]+ba/b[height<=" + h + "]/best")
//                            : "bv*+ba/best";
//                }
//            }
//
//            ProcessBuilder pb = new ProcessBuilder(
//                    yt.toAbsolutePath().toString(),
//                    "--no-warnings",
//                    "--no-playlist",
//                    "--skip-download",
//                    "--encoding", "utf-8",
//                    "-f", selector,
//                    "--print", "%(filesize)s|%(filesize_approx)s",
//                    url.trim()
//            );
//            pb.redirectErrorStream(true);
//            Process p = pb.start();
//
//            String last = null;
//            try (var br = new java.io.BufferedReader(
//                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
//                String line;
//                while ((line = br.readLine()) != null) {
//                    String s = line.trim();
//                    if (s.isEmpty()) continue;
//                    String sl = s.toLowerCase(java.util.Locale.ROOT);
//                    if (sl.startsWith("warning:")) continue;
//                    if (sl.startsWith("error:")) return null;
//                    last = s;
//                }
//            }
//
//            int code = p.waitFor();
//            if (code != 0 || last == null) return null;
//
//            String[] parts = last.split("\\|", -1);
//            if (parts.length == 0) return null;
//
//            Long exact = parseLongSafeObj(parts[0]);
//            if (exact != null && exact > 0) return exact;
//
//            if (parts.length > 1) {
//                Long approx = parseLongSafeObj(parts[1]);
//                if (approx != null && approx > 0) return approx;
//            }
//
//            return null;
//
//        } catch (Exception ignored) {
//            return null;
//        }
//    }

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

            // ✅ CACHE HERE (THIS is where your snippet belongs)
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

    private static Long parseLongSafeObj(String s) {
        try {
            if (s == null) return null;
            s = s.trim();
            if (s.isEmpty() || "NA".equalsIgnoreCase(s) || "None".equalsIgnoreCase(s)) return null;
            return Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }
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

//        downloadsList.setItems(downloadItems);
        if (filteredDownloadItems == null) {
            filteredDownloadItems = new FilteredList<>(downloadItems, r -> true);
        }
        downloadsList.setItems(filteredDownloadItems);

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

            // Keep a listener so thumbnail updates after the row is already in the list
            private javafx.beans.value.ChangeListener<String> thumbUrlListener;
            private String lastThumbUrl;

            // Keep a listener so action buttons update when state changes (cell reuse safe)
            private javafx.beans.value.ChangeListener<DownloadRow.State> stateListener;

            private void applyButtonsForState(DownloadRow.State st) {
                // reset status style each time (because cells are reused)
                try { status.setStyle(""); } catch (Exception ignored) {}
                if (st == null) st = DownloadRow.State.QUEUED;

                boolean isQueued      = st == DownloadRow.State.QUEUED;
                boolean isDownloading = st == DownloadRow.State.DOWNLOADING;
                boolean isPaused      = st == DownloadRow.State.PAUSED;
                boolean isCompleted   = st == DownloadRow.State.COMPLETED;
                boolean isFailed      = st == DownloadRow.State.FAILED || st == DownloadRow.State.CANCELLED;

                java.util.function.BiConsumer<Button, Boolean> showBtn = (btn, show) -> {
                    btn.setVisible(show);
                    btn.setManaged(show);
                };

                // Default
                showBtn.accept(pauseBtn, false);
                showBtn.accept(resumeBtn, false);
                showBtn.accept(cancelBtn, false);
                showBtn.accept(folderBtn, true);
                showBtn.accept(retryBtn, false);

                if (isDownloading) {
                    showBtn.accept(pauseBtn, true);
                    showBtn.accept(cancelBtn, true);
                } else if (isPaused) {
                    showBtn.accept(resumeBtn, true);
                    showBtn.accept(cancelBtn, true);
                } else if (isQueued) {
                    showBtn.accept(cancelBtn, true);
                } else if (isFailed) {
                    showBtn.accept(retryBtn, true);
                } else if (isCompleted) {
                    // folder only
                }

                // Safety
                if (isDownloading) {
                    showBtn.accept(retryBtn, false);
                }

                if (st == DownloadRow.State.FAILED) {
                    try { status.setStyle("-fx-text-fill: #ff5b5b;"); } catch (Exception ignored) {}
                }
            }

            private void loadThumbUrl(String url) {
                try {
                    // No URL -> show placeholder
                    if (url == null || url.isBlank()) {
                        thumb.setImage(null);
                        thumbPlaceholder.setVisible(true);
                        return;
                    }

                    // Cache hit
                    Image cached = MAIN_THUMB_CACHE.get(url);
                    if (cached != null) {
                        thumb.setImage(cached);
                        thumbPlaceholder.setVisible(false);
                        applyCoverViewport(thumb, cached, 108, 66);
                        return;
                    }

                    // Cache miss -> show placeholder while fetching
                    thumb.setImage(null);
                    thumbPlaceholder.setVisible(true);

                    // Fetch in background (avoid JavaFX URL loading issues)
                    Thread t = new Thread(() -> {
                        try {
                            byte[] bytes = fetchUrlBytes(url);
                            if (bytes == null || bytes.length == 0) return;

                            Image img = new Image(new java.io.ByteArrayInputStream(bytes));
                            if (img.isError() || img.getWidth() <= 0) return;

                            MAIN_THUMB_CACHE.put(url, img);

                            Platform.runLater(() -> {
                                // Cell reuse safety: apply only if current item still wants this url
                                DownloadRow it = getItem();
                                String cur = null;
                                try { if (it != null && it.thumbUrl != null) cur = it.thumbUrl.get(); } catch (Exception ignored) {}
                                if (cur == null || !cur.equals(url)) return;

                                thumb.setImage(img);
                                thumbPlaceholder.setVisible(false);
                                applyCoverViewport(thumb, img, 108, 66);
                            });

                        } catch (Exception ignored) {
                        }
                    }, "thumb-fetch");
                    t.setDaemon(true);
                    t.start();

                } catch (Exception ignored) {
                    thumb.setImage(null);
                    thumbPlaceholder.setVisible(true);
                }
            }

            private static byte[] fetchUrlBytes(String u) throws java.io.IOException {
                java.net.HttpURLConnection conn = null;
                try {
                    java.net.URL uu = new java.net.URL(u);
                    conn = (java.net.HttpURLConnection) uu.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(12000);
                    conn.setRequestProperty("User-Agent", "GrabX/1.0");

                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) return null;

                    try (java.io.InputStream in = conn.getInputStream()) {
                        return in.readAllBytes();
                    }
                } finally {
                    try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
                }
            }

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

            private final Label sizeLabel = new Label();

            {
                setStyle("-fx-background-color: transparent;");

                title.getStyleClass().add("gx-task-title");
                title.setWrapText(false);

                meta.getStyleClass().add("gx-task-meta");
                status.getStyleClass().add("gx-task-status");
                speed.getStyleClass().add("gx-task-status");
                eta.getStyleClass().add("gx-task-status");
                sizeLabel.getStyleClass().add("gx-task-status");
                // Fixed width to avoid jitter when numbers change
                sizeLabel.setMinWidth(180);
                sizeLabel.setPrefWidth(180);
                sizeLabel.setMaxWidth(180);
                sizeLabel.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
                // Monospace-like rendering so digits look stable
                sizeLabel.setStyle("-fx-font-family: 'Monospaced';");

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

                footerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                footerRow.setSpacing(6);

                // Use a spacer so metrics stay grouped on the far right without stretching the status text.
                final Region footerSpacer = new Region();
                HBox.setHgrow(footerSpacer, Priority.ALWAYS);

                // Metrics styling (fixed width + monospace so numbers don't "jitter")
                sizeLabel.getStyleClass().addAll("gx-task-status", "gx-task-metric");
                speed.getStyleClass().addAll("gx-task-status", "gx-task-metric");
                eta.getStyleClass().addAll("gx-task-status", "gx-task-metric");

                // IMPORTANT: force LTR for numeric metrics to avoid RTL/bidi spacing artifacts
                sizeLabel.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
                speed.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
                eta.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);

                // Use a monospace font for all numeric metrics (better on macOS)
                String metricStyle = "-fx-font-family: 'Menlo', 'Consolas', 'Monospaced'; -fx-font-size: 14px; -fx-text-fill: rgba(255,255,255,0.78);";
                sizeLabel.setStyle(metricStyle);
                speed.setStyle(metricStyle);
                eta.setStyle(metricStyle);

                // Fixed widths so the layout stays stable while values change
                sizeLabel.setMinWidth(170);
                sizeLabel.setPrefWidth(170);
                sizeLabel.setMaxWidth(170);

                speed.setMinWidth(120);
                speed.setPrefWidth(120);
                speed.setMaxWidth(120);

                eta.setMinWidth(70);
                eta.setPrefWidth(70);
                eta.setMaxWidth(70);

                // Right-align metrics
                sizeLabel.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
                speed.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
                eta.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

                // Avoid showing "..." if the width is tight; just clip
                sizeLabel.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);
                speed.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);
                eta.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);

                footerRow.getChildren().setAll(status, footerSpacer, speed, sizeLabel, eta);
                card.getStyleClass().add("gx-task-card");
                card.getChildren().addAll(headerRow, bar, footerRow);
                VBox.setVgrow(card, Priority.NEVER);

                // Actions (UI-only for now)
                pauseBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;
                    pauseDownloadRow(it);
                });

                resumeBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;
                    resumeDownloadRow(it);
                });

                cancelBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;
                    cancelDownloadRow(it);
                });

                folderBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;

                    try {
                        java.nio.file.Path outFile = null;
                        if (it.outputFile != null) outFile = it.outputFile.get();

                        // 1) لو عندي مسار الملف النهائي: اعمل Reveal/Select
                        if (outFile != null && java.nio.file.Files.exists(outFile)) {
                            revealInFileManager(outFile); // mac: open -R
                            return;
                        }

                        // 2) fallback: افتح فولدر التحميل
                        java.io.File dir = new java.io.File(it.folder);
                        if (java.awt.Desktop.isDesktopSupported() && dir.exists()) {
                            java.awt.Desktop.getDesktop().open(dir);
                        }
                    } catch (Exception ignored) {}
                });

                retryBtn.setOnAction(e -> {
                    DownloadRow it = getItem();
                    if (it == null) return;

                    // أوقف أي Process شغال
                    try { cancelDownloadRow(it); } catch (Exception ignored) {}

                    // Reset
                    it.progress.set(0);
                    it.speed.set("0 KB/s");
                    it.eta.set("--");
                    it.setState(DownloadRow.State.QUEUED);

                    if (statusText != null) statusText.setText("Retry: " + it.title.get());

                    // ابدأ من جديد (وخليها --continue عشان لو في جزء نازل يكمل)
                    startDownloadRow(it, true);
                });
            }

            @Override
            protected void updateItem(DownloadRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    // Unbind/reset metric visibility so reused cells don't keep old bindings
                    try {
                        sizeLabel.visibleProperty().unbind();
                        sizeLabel.managedProperty().unbind();
                        speed.visibleProperty().unbind();
                        speed.managedProperty().unbind();
                        eta.visibleProperty().unbind();
                        eta.managedProperty().unbind();

                        sizeLabel.setVisible(true);
                        sizeLabel.setManaged(true);
                        speed.setVisible(true);
                        speed.setManaged(true);
                        eta.setVisible(true);
                        eta.setManaged(true);
                    } catch (Exception ignored) {}
                    // Unbind/reset footer text bindings too (cell reuse safety)
                    try {
                        status.textProperty().unbind();
                        speed.textProperty().unbind();
                        eta.textProperty().unbind();
                        sizeLabel.textProperty().unbind();
                    } catch (Exception ignored) {}
                    // Unbind/reset progress bar (cell reuse safety)
                    try {
                        bar.progressProperty().unbind();
                        bar.setProgress(0);
                        bar.setVisible(true);
                        bar.setManaged(true);
                    } catch (Exception ignored) {}
                    return;
                }

                // Detach previous listener (cell reuse)
                try {
                    DownloadRow prev = (DownloadRow) getUserData();
                    if (prev != null && prev.thumbUrl != null && thumbUrlListener != null) {
                        prev.thumbUrl.removeListener(thumbUrlListener);
                    }
                } catch (Exception ignored) {}
                try {
                    DownloadRow prev = (DownloadRow) getUserData();
                    if (prev != null && prev.state != null && stateListener != null) {
                        prev.state.removeListener(stateListener);
                    }
                } catch (Exception ignored) {}

                // Attach listener to current item
                setUserData(item);
                if (thumbUrlListener == null) {
                    thumbUrlListener = (obs, oldV, newV) -> Platform.runLater(() -> loadThumbUrl(newV));
                }
                if (item.thumbUrl != null) {
                    item.thumbUrl.addListener(thumbUrlListener);
                }
                if (stateListener == null) {
                    stateListener = (obs, oldV, newV) -> Platform.runLater(() -> applyButtonsForState(newV));
                }
                try {
                    if (item.state != null) {
                        item.state.addListener(stateListener);
                    }
                } catch (Exception ignored) {}

                title.textProperty().unbind();
                title.textProperty().bind(item.title);
                meta.setText(item.mode + " • " + item.quality + " • " + item.folder);

                // Bind footer texts (cell reuse safe)
                status.textProperty().unbind();
                speed.textProperty().unbind();
                eta.textProperty().unbind();
                sizeLabel.textProperty().unbind();

                status.textProperty().bind(item.status);
                speed.textProperty().bind(item.speed);
                eta.textProperty().bind(item.eta);
                sizeLabel.textProperty().bind(item.size);

                // Thumbnail
                final String turl = (item.thumbUrl == null) ? null : item.thumbUrl.get();
                loadThumbUrl(turl);

                javafx.beans.binding.BooleanBinding isDownloading =
                        item.state.isEqualTo(DownloadRow.State.DOWNLOADING);

                // size: show only when we actually have size text
                javafx.beans.binding.BooleanBinding showSize =
                        item.size.isNotNull()
                                .and(item.size.isNotEmpty())
                                .and(item.progress.greaterThanOrEqualTo(0));

                // reset old bindings (cell reuse safety)
                sizeLabel.visibleProperty().unbind();
                sizeLabel.managedProperty().unbind();
                speed.visibleProperty().unbind();
                speed.managedProperty().unbind();
                eta.visibleProperty().unbind();
                eta.managedProperty().unbind();

                // apply rules
                sizeLabel.visibleProperty().bind(showSize);
                sizeLabel.managedProperty().bind(showSize);

                speed.visibleProperty().bind(isDownloading);
                speed.managedProperty().bind(isDownloading);

                eta.visibleProperty().bind(isDownloading);
                eta.managedProperty().bind(isDownloading);

                // Always show status (visible and managed)
                status.setVisible(true);
                status.setManaged(true);

                // Progress bar (supports indeterminate when progress < 0)
                try {
                    bar.progressProperty().unbind();
                } catch (Exception ignored) {}
                bar.progressProperty().bind(item.progress);
                bar.setVisible(true);
                bar.setManaged(true);

        // Toggle which buttons appear based on state (reactive)
        // ----------- In your download progress parsing logic, you must update the size label as described -----------
        // The below is a template for where you parse progress lines and update item.size:
        // When parsing [download] lines, set "Downloaded: <downloadedPart>" instead of "<downloaded> / <total>"
        // On completion, set "Final size: <size>" if possible.
                DownloadRow.State st = DownloadRow.State.QUEUED;
                try { st = item.state.get(); } catch (Exception ignored) {}
                applyButtonsForState(st);

                setGraphic(card);
            }
        });


        // Make it look nicer without selection highlight
        downloadsList.setSelectionModel(new NoSelectionModel<>());
    }

    private String fetchTitleWithYtDlp(String url) {
        if (url == null || url.isBlank()) return null;

        try {
            // Ensure bundled yt-dlp is extracted and runnable
            java.nio.file.Path yt = com.grabx.app.grabx.util.YtDlpManager.ensureAvailable();
            if (yt == null) return null;

            ProcessBuilder pb = new ProcessBuilder(
                    yt.toAbsolutePath().toString(),
                    "--no-warnings",
                    "--no-playlist",
                    "--skip-download",
                    "--encoding", "utf-8",
                    "--print", "title",
                    url.trim()
            );

            pb.redirectErrorStream(true);
            Process p = pb.start();

            String best = null;
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = br.readLine()) != null) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;

                    String sl = s.toLowerCase();
                    if (sl.startsWith("warning:")) continue;
                    if (sl.startsWith("error:")) return null;

                    best = s;
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


    // Lightweight title fetch without running yt-dlp (reduces "Preparing" delay)
    // Works for YouTube oEmbed.
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


    // ========= Add download item to list (single) =========
    // This method is called from Add Link dialog with:
    // addDownloadItemToList(url, folderField.getText(), modeCombo.getValue(), qualityCombo.getValue());

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

        // Thumbnail (YouTube only for now)
        try {
            String thumb = thumbFromUrl(url);
            if (thumb != null && !thumb.isBlank() && row.thumbUrl != null) {
                row.thumbUrl.set(thumb);
            }
        } catch (Exception ignored) {
        }

        // 3) أضِف في أعلى القائمة
        Platform.runLater(() -> {
            downloadItems.add(0, row);
            startDownloadRow(row, false);
        });

        // 4) حدّث شريط الحالة
        if (statusText != null) {
            statusText.setText("Queued: " + row.title.get());
        }

        // 5) جلب العنوان الحقيقي بالخلفية (بدون yt-dlp لتقليل التأخير)
// استخدام YouTube oEmbed (سريع وخفيف) بدل تشغيل yt-dlp مرة ثانية.
        if (url != null && !url.isBlank()) {
            new Thread(() -> {
                String realTitle = fetchTitleWithOEmbed(url);
                Platform.runLater(() -> {
                    if (realTitle != null && !realTitle.isBlank()) {
                        row.title.set(realTitle);
                        if (statusText != null) statusText.setText("Queued: " + realTitle);
                    } else {
                        String fallback = shorten(url);
                        if (fallback == null || fallback.isBlank()) fallback = "Unknown title";
                        row.title.set(fallback);
                        if (statusText != null) statusText.setText("Queued: " + fallback);
                    }
                });
            }, "title-oembed").start();
        }


    }


    // Only keep the version with yt-dlp --progress-template and regex patterns DEST1, DEST2, MERGE, PROG, etc.

    private void startDownloadRow(DownloadRow row, boolean resume) {
        if (row == null) return;

        // prevent duplicate runs for same row
        Process existing = activeProcesses.get(row);
        if (existing != null && existing.isAlive()) return;

        stopReasons.remove(row);

        // UI immediately: preparing (indeterminate)
        Platform.runLater(() -> {
            row.setState(DownloadRow.State.DOWNLOADING);
            row.status.set("Preparing...");
            row.size.set("");
            row.speed.set("");
            row.eta.set("");
            row.progress.set(-1); // indeterminate while yt-dlp is preparing
        });

        final String url = row.url;
        final String folder = row.folder;
        final String mode = row.mode;
        final String quality = row.quality;

        new Thread(() -> {
            Process p = null;
            final String[] lastError = new String[]{null};

            // detect output file path
            final java.util.regex.Pattern DEST1 =
                    java.util.regex.Pattern.compile("\\[download\\]\\s+Destination:\\s+(.+)$");
            final java.util.regex.Pattern DEST2 =
                    java.util.regex.Pattern.compile("\\[ExtractAudio\\]\\s+Destination:\\s+(.+)$");
            final java.util.regex.Pattern MERGE =
                    java.util.regex.Pattern.compile("\\[Merger\\]\\s+Merging formats into\\s+\\\"(.+)\\\"");

            // our progress template (percent may have padding)
            // gx:  12.3%| 1.2MiB/s| 00:12
            final java.util.regex.Pattern PROG =
                    java.util.regex.Pattern.compile(
                            "^gx:\\s*([0-9.]+)%\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)$"
                    );

            // fallback native progress line
            final java.util.regex.Pattern PROG_FALLBACK =
                    java.util.regex.Pattern.compile("^\\[download\\]\\s+([0-9.]+)%\\s+at\\s+([^\\s]+)\\s+ETA\\s+([^\\s]+).*$");

            final java.util.concurrent.atomic.AtomicBoolean startedDownloading =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            try {
                java.nio.file.Path outDir = java.nio.file.Paths.get(folder);
                java.nio.file.Files.createDirectories(outDir);

                boolean audioOnly =
                        MODE_AUDIO.equals(mode) ||
                                "Audio".equalsIgnoreCase(mode) ||
                                "Audio only".equalsIgnoreCase(mode);

                java.nio.file.Path yt = com.grabx.app.grabx.util.YtDlpManager.ensureAvailable();
                if (yt == null) throw new IllegalStateException("yt-dlp not available");

                java.util.List<String> cmd = new java.util.ArrayList<>();
                cmd.add(yt.toAbsolutePath().toString());

                cmd.add("--newline");
                cmd.add("--no-warnings");
                cmd.add("--no-playlist");

                // allow resume / pause-resume
                cmd.add("--continue");
                cmd.add("--no-overwrites");

                cmd.add("--encoding"); cmd.add("utf-8");
                cmd.add("-P"); cmd.add(outDir.toAbsolutePath().toString());
                cmd.add("-o"); cmd.add(YTDLP_OUT_TMPL);

                // progress template
                cmd.add("--progress-template");
                cmd.add(
                        "download:gx:%(progress._percent_str)s"
                                + "|%(progress._speed_str)s"
                                + "|%(progress._eta_str)s"
                                + "|%(progress.downloaded_bytes)s"
                                + "|%(progress.total_bytes)s"
                                + "|%(progress.total_bytes_estimate)s"
                );

                if (audioOnly) {
                    cmd.add("-x");
                    cmd.add("--audio-quality"); cmd.add("0");

                    String fmt = quality;
                    if (fmt == null || fmt.isBlank() || AUDIO_BEST.equals(fmt) || QUALITY_SEPARATOR.equals(fmt)) {
                        fmt = AUDIO_DEFAULT_FORMAT; // mp3
                    }
                    cmd.add("--audio-format"); cmd.add(fmt);
                    cmd.add("-f"); cmd.add("bestaudio/best");
                } else {
                    String q = (quality == null) ? QUALITY_BEST : quality;
                    String selector;

                    if (QUALITY_BEST.equals(q) || QUALITY_SEPARATOR.equals(q)) {
                        selector = "bv*+ba/best";
                    } else {
                        int h = parseHeightFromLabel(q);
                        if (h > 0) selector = "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/best";
                        else selector = "bv*+ba/best";
                    }

                    cmd.add("-f"); cmd.add(selector);
                }

                cmd.add(url);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

                p = pb.start();
                activeProcesses.put(row, p);

                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = br.readLine()) != null) {
                        String s = line.trim();
                        if (s.isEmpty()) continue;

                        if (s.startsWith("ERROR:")) lastError[0] = s;

                        // capture output path
                        try {
                            var d1 = DEST1.matcher(s);
                            var d2 = DEST2.matcher(s);
                            var mg = MERGE.matcher(s);

                            String pathStr = null;
                            if (d1.find()) pathStr = d1.group(1);
                            else if (d2.find()) pathStr = d2.group(1);
                            else if (mg.find()) pathStr = mg.group(1);

                            if (pathStr != null && !pathStr.isBlank()) {
                                String ps = pathStr.trim();
                                if ((ps.startsWith("\"") && ps.endsWith("\"")) || (ps.startsWith("'") && ps.endsWith("'"))) {
                                    ps = ps.substring(1, ps.length() - 1);
                                }
                                java.nio.file.Path finalOut = java.nio.file.Paths.get(ps);
                                Platform.runLater(() -> {
                                    try { row.outputFile.set(finalOut); } catch (Exception ignored) {}
                                });
                            }
                        } catch (Exception ignored) {}

                        // progress (preferred)
                        var m = PROG.matcher(s);
                        if (m.find()) {
                            if (startedDownloading.compareAndSet(false, true)) {
                                Platform.runLater(() -> {
                                    row.status.set("Downloading");
                                    row.size.set("");
                                    if (row.progress.get() < 0) row.progress.set(0);
                                });
                            }

                            double pct;
                            try {
                                pct = Double.parseDouble(m.group(1)) / 100.0;
                            } catch (Exception ex) {
                                pct = -1;
                            }

                            String spd = m.group(2);
                            String et  = m.group(3);

                            long downloaded = parseLongSafe(m.group(4));
                            long total = parseLongSafe(m.group(5));
                            if (total <= 0) total = parseLongSafe(m.group(6));

                            // Store raw byte counters (optional, but useful)
                            row.downloadedBytes.set(Math.max(0, downloaded));
                            row.totalBytes.set(total > 0 ? total : -1);

                            // UI size text: downloaded / total (if total known)
                            final String sizeText;
                            if (downloaded > 0 && total > 0) {
                                sizeText = formatBytesDecimal(downloaded) + " / " + formatBytesDecimal(total);
                            } else if (downloaded > 0) {
                                sizeText = formatBytesDecimal(downloaded);
                            } else {
                                sizeText = "";
                            }

                            double fpct = pct;

                            Platform.runLater(() -> {
                                row.status.set("Downloading");
                                row.size.set(sizeText == null ? "" : sizeText);

                                if (row.progress.get() < 0 && fpct >= 0) row.progress.set(fpct);
                                if (fpct >= 0) row.progress.set(fpct);

                                if (spd != null && !spd.isBlank() && !"NA".equalsIgnoreCase(spd))
                                    row.speed.set(normalizeSpeedUnit(spd));

                                if (et != null && !et.isBlank() && !"NA".equalsIgnoreCase(et))
                                    row.eta.set(et);
                            });
                            continue;
                        }

                        // progress fallback
                        var mf = PROG_FALLBACK.matcher(s);
                        if (mf.find()) {
                            if (startedDownloading.compareAndSet(false, true)) {
                                Platform.runLater(() -> {
                                    row.status.set("Downloading");
                                    if (row.progress.get() < 0) row.progress.set(0);
                                });
                            }

                            double pct;
                            try { pct = Double.parseDouble(mf.group(1)) / 100.0; } catch (Exception ex) { pct = -1; }
                            String spd = mf.group(2);
                            String et = mf.group(3);

                            double fpct = pct;
                            Platform.runLater(() -> {
                                row.status.set("Downloading");
                                if (row.progress.get() < 0 && fpct >= 0) row.progress.set(fpct);
                                if (fpct >= 0) row.progress.set(fpct);
                                if (spd != null && !spd.isBlank()) row.speed.set(normalizeSpeedUnit(spd));
                                if (et != null && !et.isBlank()) row.eta.set(et);
                            });
                            continue;
                        }

                        // phase updates during preparing
                        if (!startedDownloading.get()) {
                            // Convert noisy yt-dlp phases to a short friendly text
                            String phase = null;
                            String sl = s.toLowerCase(java.util.Locale.ROOT);

                            if (sl.contains("downloading m3u8") || sl.contains("m3u8 information")) {
                                phase = "Preparing stream...";
                            } else if (sl.contains("downloading webpage")) {
                                phase = "Preparing...";
                            } else if (sl.contains("extracting")) {
                                phase = "Extracting info...";
                            } else if (s.startsWith("[info]") || s.startsWith("[youtube]") || s.startsWith("[generic]")) {
                                phase = "Preparing...";
                            }

                            if (phase != null) {
                                final String ph = phase;
                                Platform.runLater(() -> row.status.set(ph));
                            }

                            // Switch to Downloading as soon as we see download lines
                            if (s.startsWith("[download]")) {
                                if (startedDownloading.compareAndSet(false, true)) {
                                    Platform.runLater(() -> {
                                        row.status.set("Downloading");
                                        if (row.progress.get() < 0) row.progress.set(0);
                                    });
                                }
                            }
                        }
                    }
                }

                int code = p.waitFor();
                String reason = stopReasons.get(row);

                Platform.runLater(() -> {
                    activeProcesses.remove(row);

                    if ("CANCEL".equals(reason)) {
                        row.setState(DownloadRow.State.CANCELLED);
                        row.status.set("Cancelled");
                        row.size.set("");
                        row.speed.set("");
                        row.eta.set("");
                        return;
                    }
                    if ("PAUSE".equals(reason)) {
                        row.setState(DownloadRow.State.PAUSED);
                        row.status.set("Paused");
                        row.size.set("");
                        row.speed.set("");
                        row.eta.set("");
                        return;
                    }

                    if (code == 0) {
                        row.setState(DownloadRow.State.COMPLETED);
                        row.status.set("Completed");
                        // CHANGED: set final size from disk if possible
                        try {
                            java.nio.file.Path out = null;
                            if (row.outputFile != null) out = row.outputFile.get();
                            if (out != null && java.nio.file.Files.exists(out)) {
                                long sz = java.nio.file.Files.size(out);
                                row.size.set(formatBytesDecimal(sz));
//                                row.size.set("Final size: " + formatBytesDecimal(sz));
                            } else {
                                row.size.set("");
                            }
                        } catch (Exception ignored) {
                            row.size.set("");
                        }
                        row.progress.set(1.0);
                        row.speed.set("");
                        row.eta.set("");
                    } else {
                        row.setState(DownloadRow.State.FAILED);
                        row.status.set("Failed");
                        row.size.set("");
                        row.speed.set("");
                        row.eta.set("");
                    }
                });

            } catch (Exception ex) {
                final Process fp = p;
                Platform.runLater(() -> {
                    try { if (fp != null) fp.destroyForcibly(); } catch (Exception ignored) {}
                    activeProcesses.remove(row);
                    row.setState(DownloadRow.State.FAILED);
                    row.status.set("Failed");
                    row.size.set("");
                    row.speed.set("");
                    row.eta.set("");
                });
            }
        }, "yt-dlp-download").start();
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

    private static long parseLongSafe(String s) {
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
    private static String formatBytesDecimal(long bytes) {
        if (bytes <= 0) return "0 B";
        double b = (double) bytes;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (b >= 1000.0 && i < u.length - 1) {
            b /= 1000.0;
            i++;
        }
        return String.format(java.util.Locale.US, "%.2f %s", b, u[i]);
    }

    private static String normalizeSpeedUnit(String spd) {
        if (spd == null) return null;
        String s = spd.trim();
        if (s.isEmpty()) return s;

        // Convert binary units to decimal-looking units for consistency with size (KB/MB/GB)
        s = s.replace("KiB/s", "KB/s")
             .replace("MiB/s", "MB/s")
             .replace("GiB/s", "GB/s")
             .replace("TiB/s", "TB/s");

        // Ensure spacing is consistent (e.g., "256.50KB/s" -> "256.50 KB/s")
        s = s.replaceAll("(?i)(\\d)(KB/s|MB/s|GB/s|TB/s)$", "$1 $2");
        return s;
    }

    private void pauseDownloadRow(DownloadRow row) {
        if (row == null) return;
        Process p = activeProcesses.get(row);
        if (p == null || !p.isAlive()) {
            row.setState(DownloadRow.State.PAUSED);
            return;
        }
        stopReasons.put(row, "PAUSE");
        try { p.destroy(); } catch (Exception ignored) {}
        try { p.destroyForcibly(); } catch (Exception ignored) {}
        row.setState(DownloadRow.State.PAUSED);
    }

    private void cancelDownloadRow(DownloadRow row) {
        if (row == null) return;
        Process p = activeProcesses.get(row);
        stopReasons.put(row, "CANCEL");
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        }
        row.setState(DownloadRow.State.CANCELLED);
    }

    private void resumeDownloadRow(DownloadRow row) {
        if (row == null) return;
        DownloadRow.State st;
        try { st = row.state.get(); } catch (Exception e) { st = DownloadRow.State.QUEUED; }
        if (st == DownloadRow.State.DOWNLOADING) return;

        stopReasons.remove(row);
        startDownloadRow(row, true); // --continue
    }



    // --- Thumbnail helpers and cache ---
    private static final java.util.Map<String, javafx.scene.image.Image> MAIN_THUMB_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String extractYoutubeId(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;

        // Try common patterns:
        // - youtu.be/<id>
        // - watch?v=<id>
        // - /shorts/<id>
        // - /embed/<id>
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

    public static String thumbFromUrl(String url) {
        String id = extractYoutubeId(url);
        if (id == null || id.isBlank()) return null;
        return "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";
    }

    // To git the selected file in folder
    private void revealInFileManager(Path file) {
        try {
            if (file == null) return;

            String os = System.getProperty("os.name", "").toLowerCase();

            if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", file.toAbsolutePath().toString()).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
            } else {
                // Linux: best effort
                Path parent = file.getParent();
                if (parent != null) new ProcessBuilder("xdg-open", parent.toAbsolutePath().toString()).start();
            }
        } catch (Exception ignored) {}
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
    // Split a JSON array like [ {...}, {...}, ... ] into top-level object strings (no external JSON lib)
    private static java.util.List<String> splitTopLevelJsonObjects(String jsonArray) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (jsonArray == null || jsonArray.isBlank()) return out;

        int i = 0;
        // find first '{'
        while (i < jsonArray.length() && jsonArray.charAt(i) != '{') i++;
        if (i >= jsonArray.length()) return out;

        boolean inStr = false;
        char prev = 0;
        int depth = 0;
        int start = -1;

        for (; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);

            if (c == '"' && prev != '\\') inStr = !inStr;

            if (!inStr) {
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        out.add(jsonArray.substring(start, i + 1));
                        start = -1;
                    }
                }
            }

            prev = c;
        }

        return out;
    }

    private static String extractStringFieldFast(String jsonChunk, String field) {
        if (jsonChunk == null || jsonChunk.isBlank() || field == null || field.isBlank()) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\\"" + java.util.regex.Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
                    .matcher(jsonChunk);
            if (!m.find()) return null;
            String v = m.group(1);
            return (v == null) ? null : v.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double extractDoubleFieldFast(String jsonChunk, String field) {
        if (jsonChunk == null || jsonChunk.isBlank() || field == null || field.isBlank()) return 0.0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\\"" + java.util.regex.Pattern.quote(field) + "\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                    .matcher(jsonChunk);
            if (!m.find()) return 0.0;
            return Double.parseDouble(m.group(1));
        } catch (Exception ignored) {
            return 0.0;
        }
    }

}