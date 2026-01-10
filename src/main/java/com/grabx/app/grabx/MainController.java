package com.grabx.app.grabx;

import java.util.Map;
import java.util.concurrent.*;


import com.grabx.app.grabx.ui.components.ScrollbarAutoHide;
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

//    private static void probeVideoQualitiesAsync(String videoUrl, String videoId, java.util.function.Consumer<ProbeQualitiesResult> onDone) {
//        if (videoUrl == null || videoUrl.isBlank() || videoId == null || videoId.isBlank()) return;
//
//        // cache hit
//        ProbeQualitiesResult cached = PLAYLIST_PROBE_CACHE.get(videoId);
//        if (cached != null) {
//            Platform.runLater(() -> onDone.accept(cached));
//            return;
//        }
//
//        // avoid duplicate in-flight probes for same item
//        if (!PLAYLIST_PROBE_INFLIGHT.add(videoId)) return;
//
//        try {
//            PLAYLIST_PROBE_EXEC.execute(() -> {
//                try {
//                    ProbeQualitiesResult pr = probeQualitiesWithSizes(videoUrl);
//                    PLAYLIST_PROBE_CACHE.put(videoId, pr);
//                    Platform.runLater(() -> onDone.accept(pr));
//                } finally {
//                    PLAYLIST_PROBE_INFLIGHT.remove(videoId);
//                }
//            });
//        } catch (RejectedExecutionException ignored) {
//            // queue full -> we'll probe later when user scrolls again
//            PLAYLIST_PROBE_INFLIGHT.remove(videoId);
//        }
//    }

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

    @FXML private Label statusText;
    @FXML private BorderPane root;

    @FXML private Button pauseAllButton;
    @FXML private Button resumeAllButton;
    @FXML private Button clearAllButton;

    @FXML private Button addLinkButton;
    @FXML private Button settingsButton;

    @FXML private Label contentTitle;
    @FXML private ListView<SidebarItem> sidebarList;

    // ========= Actions =========

    @FXML
    public void onAddLink(ActionEvent event) {
        showAddLinkDialog();
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
        } catch (Exception ignored) {}

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
        return  Math.round(mb) + " MB";
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
        } catch (Exception ignored) {}

        heights = normalizeHeights(heights);
        sizeByHeight.keySet().retainAll(heights);
        return new ProbeQualitiesResult(heights, sizeByHeight);
    }

    private static final class ProbeQualitiesResult {
        final java.util.Set<Integer> heights;
        final java.util.Map<Integer, String> sizeByHeight;
        ProbeQualitiesResult(java.util.Set<Integer> heights, java.util.Map<Integer, String> sizeByHeight) {
            this.heights = heights;
            this.sizeByHeight = sizeByHeight;
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

        installTooltips();

        // ✅ Make hover/press work on the whole Button (not only the icon node)
        normalizeIconButton(pauseAllButton);
        normalizeIconButton(resumeAllButton);
        normalizeIconButton(clearAllButton);
        normalizeIconButton(addLinkButton);
        normalizeIconButton(settingsButton);

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
        installTooltip(pauseAllButton,  "Pause all");
        installTooltip(resumeAllButton, "Resume all");
        installTooltip(clearAllButton,  "Clear all");
        installTooltip(addLinkButton,   "Add link");
        installTooltip(settingsButton,  "Settings");
    }

    private void installTooltip(Button btn, String text) {
        if (btn == null) return;

        Tooltip tip = new Tooltip(text);
        tip.getStyleClass().add("gx-tooltip");

        // Stable delays (no blinking)
        Duration showDelay = Duration.millis(220);
        Duration hideDelay = Duration.millis(80);

        PauseTransition showTimer = new PauseTransition(showDelay);
        PauseTransition hideTimer = new PauseTransition(hideDelay);

        Runnable showUnderButton = () -> {
            Bounds b = btn.localToScreen(btn.getBoundsInLocal());
            if (b == null) return;

            // show roughly under button (will adjust again after layout)
            if (!tip.isShowing()) {
                tip.show(btn, b.getMinX(), b.getMaxY() + 6);
            }

            Platform.runLater(() -> {
                if (!btn.isHover() || !tip.isShowing()) return;

                Bounds bb = btn.localToScreen(btn.getBoundsInLocal());
                if (bb == null) return;

                try {
                    var w = tip.getScene().getWindow();
                    // place centered under button
                    double x = bb.getMinX() + (bb.getWidth() - w.getWidth()) / 2.0;
                    double y = bb.getMaxY() + 6;
                    w.setX(x);
                    w.setY(y);
                } catch (Exception ignored) {}
            });
        };

        showTimer.setOnFinished(e -> {
            if (!btn.isHover()) return;
            hideTimer.stop();
            showUnderButton.run();
        });

        hideTimer.setOnFinished(e -> tip.hide());

        btn.hoverProperty().addListener((obs, wasHover, isHover) -> {
            if (isHover) {
                hideTimer.stop();
                showTimer.playFromStart();
            } else {
                showTimer.stop();
                hideTimer.playFromStart();
            }
        });

        // Clicking should hide immediately
        btn.armedProperty().addListener((obs, wasArmed, isArmed) -> {
            if (isArmed) {
                showTimer.stop();
                hideTimer.stop();
                tip.hide();
            }
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

        // Only qualities that actually exist (descending)
//        java.util.List<Integer> sorted = heights.stream()
//                .filter(h -> h != null && h > 0)
//                .distinct()
//                .sorted(java.util.Comparator.reverseOrder())
//                .toList();

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

    private void showAddLinkDialog() {

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Link");
        dialog.setHeaderText(null);

        DialogPane pane = dialog.getDialogPane();

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

        GridPane grid = new GridPane();
        grid.getStyleClass().add("gx-dialog-grid");
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(Insets.EMPTY);

        // URL
        TextField urlField = new TextField();
        urlField.setPromptText("Paste URL...");
        urlField.getStyleClass().add("gx-input");

        // GET button (analyze)
        Button getBtn = new Button("Get");
        getBtn.getStyleClass().addAll("gx-btn", "gx-btn-ghost");

        getBtn.setMinWidth(90);

        // Mode + Quality (disabled until analyzed as VIDEO)
        ComboBox<String> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll("Video", "Audio only");
        modeCombo.getSelectionModel().selectFirst();
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
        final ContentType[] lastType = { ContentType.UNSUPPORTED };

        Runnable applyTypeToUi = () -> {
            ContentType t = lastType[0];

            if (t == ContentType.VIDEO) {
                modeCombo.setDisable(false);
                qualityCombo.setDisable(false);
                info.setText("Detected: Video. Choose mode/quality then Add & Start.");
                okBtn.setDisable(false);
            } else if (t == ContentType.PLAYLIST) {
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Detected: Playlist. Opening Playlist screen...");
                // Add is handled from the Playlist screen
                okBtn.setDisable(true);
            } else if (t == ContentType.DIRECT_FILE) {
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Detected: Direct file/link. Ready to Add & Start.");
                okBtn.setDisable(false);
            } else {
                modeCombo.setDisable(true);
                qualityCombo.setDisable(true);
                info.setText("Unsupported or invalid URL.");
                okBtn.setDisable(true);
            }
        };

        getBtn.setOnAction(e -> {
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            lastType[0] = analyzeUrlType(url);

            // If it's a single video, probe available heights and rebuild the quality list accordingly
            if (lastType[0] == ContentType.VIDEO) {
                info.setText("Analyzing formats...");

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
//            info.setText("Paste a link then click Get.");
            fillQualityCombo(qualityCombo);
            info.setText("Paste a link then click Get.");
        });

        pane.setContent(grid);
        pane.setPrefWidth(760);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != addStartBtn) return;

        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        ContentType t = lastType[0];

        // Temporary behavior until we wire the real downloader engine:
        if (statusText != null) {
            if (t == ContentType.VIDEO) {
                statusText.setText("Start: " + shorten(url) + " | " + modeCombo.getValue() + " | " + qualityCombo.getValue());
            } else if (t == ContentType.DIRECT_FILE) {
                statusText.setText("Start: " + shorten(url) + " | Direct");
            } else if (t == ContentType.PLAYLIST) {
                statusText.setText("Playlist detected (UI next): " + shorten(url));
            } else {
                statusText.setText("Unsupported: " + shorten(url));
            }
        }

        // TODO NEXT STEP:
        // 1) if PLAYLIST: openPlaylistDialog(url)
        // 2) if VIDEO: probe formats (yt-dlp -J/-F) then start task
        // 3) if DIRECT_FILE: enqueue direct download task
    }


    // ========= Playlist Screen (v1 - lightweight) =========

    private void openPlaylistWindow(String playlistUrl, String folder) {

        Stage stage = new Stage();
        stage.setTitle("Playlist");
        stage.initModality(Modality.APPLICATION_MODAL);

        VBox rootBox = new VBox(12);
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

        ComboBox<String> globalQualityCombo = new ComboBox<>();
        globalQualityCombo.getStyleClass().addAll("gx-combo", "gx-playlist-quality");
        globalQualityCombo.setPrefWidth(260);

        // standard list; we will map to closest supported per item
        fillQualityCombo(globalQualityCombo);

        // separator behavior
        globalQualityCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setDisable(QUALITY_SEPARATOR.equals(item));
                setOpacity(QUALITY_SEPARATOR.equals(item) ? 0.55 : 1.0);
            }
        });
        globalQualityCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });

// store desired global quality
        StringProperty globalDesiredQuality = new SimpleStringProperty(QUALITY_BEST);
        globalQualityCombo.getSelectionModel().select(QUALITY_BEST);
//        globalDesiredQuality.bind(globalQualityCombo.valueProperty());

        globalQualityCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            if (QUALITY_SEPARATOR.equals(newV)) return;
            if (QUALITY_CUSTOM.equals(newV)) return;
            globalDesiredQuality.set(newV);
        });

        HBox globalRow = new HBox(10);
        globalRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        globalRow.getChildren().addAll(globalQLabel, globalQualityCombo);

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

        anyQualityPopupOpen.addListener((o, was, isNow) -> {
            if (!isNow && pendingRefresh.getAndSet(false)) {
                refreshThrottle.stop();
                refreshThrottle.setOnFinished(ev -> list.refresh());
                refreshThrottle.playFromStart();
            }
        });

// Stop background prefetch when window closes
        java.util.concurrent.atomic.AtomicBoolean stopPrefetch = new java.util.concurrent.atomic.AtomicBoolean(false);
        stage.setOnHidden(ev -> stopPrefetch.set(true));

        // ===== Union of discovered heights across playlist for the GLOBAL combo =====
        java.util.Set<Integer> globalHeightsUnion = new java.util.concurrent.ConcurrentSkipListSet<>();

        PauseTransition globalComboUpdateThrottle = new PauseTransition(Duration.millis(220));
        Runnable updateGlobalQualityCombo = () -> {
            globalComboUpdateThrottle.stop();
            globalComboUpdateThrottle.setOnFinished(ev -> {
                String prev = globalQualityCombo.getValue();
                if (prev == null || prev.isBlank()) prev = QUALITY_BEST;

                if (globalHeightsUnion.isEmpty()) {
                    fillQualityCombo(globalQualityCombo);
                } else {
                    java.util.Set<Integer> normalized = normalizeHeights(new java.util.HashSet<>(globalHeightsUnion));
                    java.util.List<Integer> sorted = normalized.stream()
                            .sorted(java.util.Comparator.reverseOrder())
                            .toList();

                    globalQualityCombo.getItems().clear();
                    globalQualityCombo.getItems().add(QUALITY_BEST);
                    globalQualityCombo.getItems().add(QUALITY_SEPARATOR);
                    for (Integer h : sorted) {
                        globalQualityCombo.getItems().add(formatHeightLabel(h));
                    }
                }

                // Restore selection if possible, otherwise map to closest
                if (!globalQualityCombo.getItems().contains(prev)) {
                    String mapped = pickClosestSupportedQuality(prev, new java.util.ArrayList<>(globalQualityCombo.getItems()));
                    globalQualityCombo.getSelectionModel().select(mapped);
                } else {
                    globalQualityCombo.getSelectionModel().select(prev);
                }
            });
            globalComboUpdateThrottle.playFromStart();
        };

        final javafx.beans.property.BooleanProperty updatingGlobalCombo =
                new javafx.beans.property.SimpleBooleanProperty(false);

        Runnable updateGlobalMixedState = () -> {
            if (updatingGlobalCombo.get()) return;

            java.util.Set<String> qs = new java.util.HashSet<>();
            boolean anyManual = false;

            for (PlaylistEntry it : items) {
                if (it == null) continue;
                if (!it.isSelected()) continue;
                if (it.isUnavailable()) continue;

                if (it.isManualQuality()) anyManual = true;

                String q = it.getQuality();
                if (q == null || q.isBlank()) q = QUALITY_BEST;
                qs.add(q);
                if (qs.size() > 1) break;
            }

            updatingGlobalCombo.set(true);
            try {
                if (qs.isEmpty()) {
                    globalQualityCombo.getSelectionModel().select(QUALITY_BEST);
                } else if (qs.size() == 1 && !anyManual) {
                    globalQualityCombo.getSelectionModel().select(qs.iterator().next());
                } else {
                    if (!globalQualityCombo.getItems().contains(QUALITY_CUSTOM)) {
                        globalQualityCombo.getItems().add(0, QUALITY_CUSTOM);
                    }
                    globalQualityCombo.getSelectionModel().select(QUALITY_CUSTOM);
                }
            } finally {
                updatingGlobalCombo.set(false);
            }
        };

        list.setCellFactory(lv -> new ListCell<>() {

            private final CheckBox cb = new CheckBox();
            private final Label title = new Label();
            private final Label meta = new Label();

            private final VBox textBox = new VBox(4);

            private final StackPane thumbBox = new StackPane();
            private final ImageView thumb = new ImageView();
            private final Label placeholder = new Label("NO PREVIEW");

            private final ComboBox<String> qualityCombo = new ComboBox<>();

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
                qualityCombo.setPrefWidth(200);
                qualityCombo.setMaxWidth(220);
                qualityCombo.setDisable(true);

                qualityCombo.setCellFactory(x -> new ListCell<>() {
                    @Override protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty ? null : item);
                        setDisable(QUALITY_SEPARATOR.equals(item));
                        setOpacity(QUALITY_SEPARATOR.equals(item) ? 0.55 : 1.0);
                    }
                });
                qualityCombo.setButtonCell(new ListCell<>() {
                    @Override protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty ? null : item);
                    }
                });

                // Prevent refresh from closing the popup while user is choosing a quality
                qualityCombo.showingProperty().addListener((obs, was, isNow) -> anyQualityPopupOpen.set(isNow));

                qualityCombo.valueProperty().addListener((obs, old, val) -> {
                    if (val == null || QUALITY_SEPARATOR.equals(val)) return;
                    PlaylistEntry it = getItem();
                    if (it == null) return;

                    it.setQuality(val);
                    it.setManualQuality(true);   // ✅ هذا صار اختيار يدوي
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
                    Platform.runLater(updateGlobalMixedState);
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
                    fillQualityCombo(qualityCombo);
                    qualityCombo.getSelectionModel().select(item.getQuality());

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

                        // ✅ pick desired: global unless manual
                        String desired = item.getQuality();
                        if (!item.isManualQuality()) {
                            desired = globalDesiredQuality.get();
                            if (desired == null || desired.isBlank()) desired = QUALITY_BEST;
                        }

                        // ✅ map desired to closest supported for THIS item
                        String supported = pickClosestSupportedQuality(desired, item.getAvailableQualities());
                        item.setQuality(supported);
                        Platform.runLater(updateGlobalMixedState);

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

                    } else {
                        // Probe failed/rejected -> allow retry later instead of getting stuck disabled forever
                        item.setQualitiesLoaded(false);

                        qualityCombo.getItems().setAll(QUALITY_BEST, QUALITY_SEPARATOR);
                        qualityCombo.getSelectionModel().select(QUALITY_BEST);
                        qualityCombo.setDisable(true);
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

//                java.util.List<Integer> sorted = heights.stream()
//                        .filter(h -> h != null && h > 0)
//                        .distinct()
//                        .sorted(java.util.Comparator.reverseOrder())
//                        .toList();

                java.util.List<Integer> sorted = new java.util.ArrayList<>(heights);
                sorted.removeIf(h -> h == null || h <= 0);
                sorted.sort(java.util.Comparator.reverseOrder());

                for (Integer h : sorted) labels.add(formatHeightLabel(h));
                it.setAvailableQualities(labels);

                // size map: label -> "~xxx MB"
                java.util.Map<String, String> sizeMap = new java.util.HashMap<>();
                if (pr != null) {
                    for (Integer h : sorted) {
                        String label = formatHeightLabel(h);
                        String sz = pr.sizeByHeight.get(h);
                        if (sz != null) sizeMap.put(label, sz);
                    }
                }
                it.setSizeByQuality(sizeMap);

                // pick desired: global unless manual
                String desired = it.getQuality();
                if (!it.isManualQuality()) {
                    desired = globalDesiredQuality.get();
                    if (desired == null || desired.isBlank()) desired = QUALITY_BEST;
                }

                String supported = pickClosestSupportedQuality(desired, it.getAvailableQualities());
                it.setQuality(supported);

                requestRefreshSafe.run();
            });

            if (!queued) {
                // queue full -> allow retry later
                it.setQualitiesLoaded(false);
            }
        };

        globalQualityCombo.valueProperty().addListener((obs, old, val) -> {
            if (val == null || QUALITY_SEPARATOR.equals(val)) return;
            if (QUALITY_CUSTOM.equals(val)) return;
            if (updatingGlobalCombo.get()) return;

            for (PlaylistEntry it : items) {
                if (it == null) continue;
                if (!it.isSelected()) continue;
                if (it.isUnavailable()) continue;

                it.setManualQuality(false);
                it.setQuality(val);
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

                // default select all (skip unavailable)
                for (PlaylistEntry it : items) {
                    it.setSelected(!it.isUnavailable());
                }
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
                    } catch (InterruptedException ignored) {}
                }, "playlist-prefetch").start();
            });
        }, "probe-playlist").start();

        stage.showAndWait();
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


    // Disable ListView selection (we use checkboxes for selection instead)
    private static final class NoSelectionModel<T> extends MultipleSelectionModel<T> {
        @Override public ObservableList<Integer> getSelectedIndices() { return FXCollections.emptyObservableList(); }
        @Override public ObservableList<T> getSelectedItems() { return FXCollections.emptyObservableList(); }
        @Override public void selectIndices(int index, int... indices) {}
        @Override public void selectAll() {}
        @Override public void clearAndSelect(int index) {}
        @Override public void select(int index) {}
        @Override public void select(T obj) {}
        @Override public void clearSelection(int index) {}
        @Override public void clearSelection() {}
        @Override public boolean isSelected(int index) { return false; }
        @Override public boolean isEmpty() { return true; }
        @Override public void selectPrevious() {}
        @Override public void selectNext() {}
        @Override public void selectFirst() {}
        @Override public void selectLast() {}
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 46 ? s.substring(0, 43) + "..." : s;
    }

}
