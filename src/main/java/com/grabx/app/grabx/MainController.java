//package com.grabx.app.grabx;
//
//import com.grabx.app.grabx.ui.components.ScrollbarAutoHide;
//import com.grabx.app.grabx.ui.sidebar.SidebarItem;
//import javafx.animation.PauseTransition;
//import javafx.application.Platform;
//import javafx.event.ActionEvent;
//import javafx.fxml.FXML;
//import javafx.geometry.Bounds;
//import javafx.scene.control.*;
//import javafx.scene.layout.BorderPane;
//import javafx.util.Duration;
//
//public class MainController {
//
//    @FXML private Label statusText;
//    @FXML private BorderPane root;
//
//    @FXML private Button pauseAllButton;
//    @FXML private Button resumeAllButton;
//    @FXML private Button clearAllButton;
//
//    @FXML private Button addLinkButton;
//    @FXML private Button settingsButton;
//
//    @FXML private Label contentTitle;
//
//    @FXML private ListView<SidebarItem> sidebarList;
//
//    @FXML
//    public void onAddLink(ActionEvent event) {
//        if (statusText != null) statusText.setText("Add Link clicked");
//    }
//
//    @FXML
//    public void onSettings(ActionEvent event) {
//        if (statusText != null) statusText.setText("Settings clicked");
//    }
//
//    @FXML
//    public void onMiniMode(ActionEvent event) {
//        if (statusText != null) statusText.setText("Mini Mode clicked");
//    }
//
//    @FXML
//    public void onPauseAll(ActionEvent actionEvent) {
//        if (statusText != null) statusText.setText("Pause all");
//    }
//
//    @FXML
//    public void onResumeAll(ActionEvent actionEvent) {
//        if (statusText != null) statusText.setText("Resume all");
//    }
//
//    @FXML
//    public void onClearAll(ActionEvent actionEvent) {
//        if (statusText != null) statusText.setText("Clear all");
//    }
//
//    @FXML
//    public void initialize() {
//
//
//        Platform.runLater(() -> {
//            ScrollbarAutoHide.enableGlobalAutoHide(root);
//        });
//
//        Platform.runLater(() -> root.requestFocus());
//        installTooltips();
//
//        // ✅ Make hover/press work on the whole Button (not only the icon node)
//        normalizeIconButton(pauseAllButton);
//        normalizeIconButton(resumeAllButton);
//        normalizeIconButton(clearAllButton);
//        normalizeIconButton(addLinkButton);
//        normalizeIconButton(settingsButton);
//
//        sidebarList.getItems().setAll(
//                new SidebarItem("ALL", "All"),
//                new SidebarItem("DOWNLOADING", "Downloading"),
//                new SidebarItem("PAUSED", "Paused"),
//                new SidebarItem("COMPLETED", "Completed"),
//                new SidebarItem("CANCELLED", "Cancelled")
//        );
//
//        // ✅ Keep consistent row height, but allow the ListView to grow with the sidebar
//        sidebarList.setFixedCellSize(44);
//        sidebarList.setPrefHeight(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
//        sidebarList.setMaxHeight(Double.MAX_VALUE);
//
//        // عرض الاسم داخل الخلايا
//        sidebarList.setCellFactory(lv -> new ListCell<>() {
//            @Override
//            protected void updateItem(SidebarItem item, boolean empty) {
//                super.updateItem(item, empty);
//                setText((empty || item == null) ? null : item.getTitle());
//            }
//        });
//
//        sidebarList.getSelectionModel().selectFirst();
//
//        SidebarItem first = sidebarList.getSelectionModel().getSelectedItem();
//        if (contentTitle != null && first != null) contentTitle.setText(first.getTitle());
//
//        sidebarList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
//            if (newV == null) return;
//
//            if (contentTitle != null) contentTitle.setText(newV.getTitle());
//            if (statusText != null) statusText.setText("Filter: " + newV.getTitle());
//
//            // TODO لاحقاً: applyFilter(newV.getKey());
//        });
//    }
//
//
//    private void normalizeIconButton(Button btn) {
//        if (btn == null) return;
//
//        btn.setPickOnBounds(true);
//
//        if (btn.getGraphic() != null) {
//            btn.getGraphic().setMouseTransparent(true);
//        }
//
//
//        if (btn.getGraphic() != null) {
//            btn.getGraphic().getStyleClass().remove("gx-icon-btn");
//        }
//    }
//
//    private void installTooltips() {
//        installTooltip(pauseAllButton,  "Pause all");
//        installTooltip(resumeAllButton, "Resume all");
//        installTooltip(clearAllButton,  "Clear all");
//        installTooltip(addLinkButton,   "Add link");
//        installTooltip(settingsButton,  "Settings");
//    }
//
//
//    private void installTooltip(Button btn, String text) {
//        if (btn == null) return;
//
//        Tooltip tip = new Tooltip(text);
//        tip.getStyleClass().add("gx-tooltip");
//
//        Duration showDelay = Duration.millis(10);
//        Duration hideDelay = Duration.millis(40);
//
//        PauseTransition showTimer = new PauseTransition(showDelay);
//        PauseTransition hideTimer = new PauseTransition(hideDelay);
//
//        Runnable showUnderButton = () -> {
//            Bounds b = btn.localToScreen(btn.getBoundsInLocal());
//            if (b == null) return;
//
//            // Show once (position will be adjusted after CSS/layout pass)
//            if (!tip.isShowing()) {
//                tip.show(btn, b.getMinX(), b.getMaxY());
//            }
//
//            Platform.runLater(() -> {
//                if (!btn.isHover() || !tip.isShowing()) return;
//
//                Bounds bb = btn.localToScreen(btn.getBoundsInLocal());
//                if (bb == null) return;
//
//                try {
//                    var w = tip.getScene().getWindow(); // PopupWindow غالبًا
//
//
//                    Platform.runLater(() -> {
//                        if (!btn.isHover() || !tip.isShowing()) return;
//
//                        Bounds bb2 = btn.localToScreen(btn.getBoundsInLocal());
//                        if (bb2 == null) return;
//
//                        double y = bb2.getMaxY();
//                        w.setY(y);
//
//                    });
//
//                } catch (Exception ignored) {}
//            });
//        };
//
//        showTimer.setOnFinished(e -> {
//            if (!btn.isHover()) return;
//            hideTimer.stop();
//            showUnderButton.run();
//        });
//
//        hideTimer.setOnFinished(e -> tip.hide());
//
//        btn.hoverProperty().addListener((obs, wasHover, isHover) -> {
//            if (isHover) {
//                hideTimer.stop();
//                showTimer.playFromStart();
//            } else {
//                showTimer.stop();
//                hideTimer.playFromStart();
//            }
//        });
//
//        // Clicking should hide immediately
//        btn.armedProperty().addListener((obs, wasArmed, isArmed) -> {
//            if (isArmed) {
//                showTimer.stop();
//                hideTimer.stop();
//                tip.hide();
//            }
//        });
//    }
//}



package com.grabx.app.grabx;

import com.grabx.app.grabx.ui.components.ScrollbarAutoHide;
import com.grabx.app.grabx.ui.sidebar.SidebarItem;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.net.URL;
import java.util.Optional;

public class MainController {

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

    // ========= Add Link dialog =========

    private void showAddLinkDialog() {

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Link");
        dialog.setHeaderText(null); // خليها null عشان ما يطلع Header افتراضي


        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("gx-dialog");
        pane.setPadding(Insets.EMPTY);

        // ✅ حمّل ملفات CSS مباشرة (مضمون)
        pane.getStylesheets().addAll(
                getClass().getResource("/com/grabx/app/grabx/styles/theme-base.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/layout.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/buttons.css").toExternalForm(),
                getClass().getResource("/com/grabx/app/grabx/styles/sidebar.css").toExternalForm()
        );

        // ✅ Buttons
        ButtonType addToQueue = new ButtonType("Add to Queue", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, addToQueue);

        // ✅ Content
        GridPane grid = new GridPane();
        grid.getStyleClass().add("gx-dialog-grid");
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(0, 0, 0, 0));

        TextField urlField = new TextField();
        urlField.setPromptText("Paste URL...");
        urlField.getStyleClass().add("gx-input");

        ComboBox<String> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll("Video (default)", "Audio", "Auto");
        modeCombo.getSelectionModel().select(0);
        modeCombo.getStyleClass().add("gx-combo");

        ComboBox<String> qualityCombo = new ComboBox<>();
        qualityCombo.getItems().addAll("Auto (recommended)", "240p", "360p", "480p", "720p", "1080p");
        qualityCombo.getSelectionModel().select(0);
        qualityCombo.getStyleClass().add("gx-combo");

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

        CheckBox startNow = new CheckBox("Start immediately");
        startNow.setSelected(true);
        startNow.getStyleClass().add("gx-check");

        Label tip = new Label("Tip: Playlist URLs will be detected automatically. You can choose per-item quality later.");
        tip.getStyleClass().add("gx-text-muted");
        tip.setWrapText(true);

        grid.add(new Label("URL"), 0, 0);
        grid.add(urlField, 1, 0, 2, 1);

        grid.add(new Label("Mode"), 0, 1);
        grid.add(modeCombo, 1, 1);

        grid.add(new Label("Quality"), 0, 2);
        grid.add(qualityCombo, 1, 2);

        grid.add(new Label("Folder"), 0, 3);
        grid.add(folderField, 1, 3);
        grid.add(browseBtn, 2, 3);

        grid.add(startNow, 1, 4, 2, 1);
        grid.add(tip, 1, 5, 2, 1);

        GridPane.setHgrow(urlField, Priority.ALWAYS);
        GridPane.setHgrow(folderField, Priority.ALWAYS);
        GridPane.setHgrow(modeCombo, Priority.ALWAYS);
        GridPane.setHgrow(qualityCombo, Priority.ALWAYS);

        pane.setContent(grid);
        pane.setPrefWidth(760);

        Button okBtn = (Button) pane.lookupButton(addToQueue);
        okBtn.disableProperty().bind(urlField.textProperty().isEmpty());

        // ✅ خلي حجم الديالوج مرتب
        pane.setPrefWidth(680);

        dialog.showAndWait();
    }

//    public void showAddLinkDialog() {
//
//        Dialog<ButtonType> dialog = new Dialog<>();
//        dialog.setTitle("Add Link");
//
//        DialogPane pane = dialog.getDialogPane();
//
//        // ✅ أهم سطرين: اربط CSS + ضيف class
//        pane.getStyleClass().add("gx-dialog");
//
//
//        URL theme = getClass().getResource("/com.grabx.app.grabx/styles/theme-base.css");
//        URL layout = getClass().getResource("/com.grabx.app.grabx/styles/layout.css");
//        URL buttons = getClass().getResource("/com.grabx.app.grabx/styles/buttons.css");
//        URL sidebar = getClass().getResource("/com.grabx.app.grabx/styles/sidebar.css");
//
//        if (theme != null) pane.getStylesheets().add(theme.toExternalForm());
//        if (layout != null) pane.getStylesheets().add(layout.toExternalForm());
//        if (buttons != null) pane.getStylesheets().add(buttons.toExternalForm());
//        if (sidebar != null) pane.getStylesheets().add(sidebar.toExternalForm());
//
//        // ✅ أزرار الديالوج
//        ButtonType addToQueue = new ButtonType("Add to Queue", ButtonBar.ButtonData.OK_DONE);
//        pane.getButtonTypes().addAll(ButtonType.CANCEL, addToQueue);
//
//        // ✅ محتوى الديالوج
//        GridPane grid = new GridPane();
//        grid.getStyleClass().add("gx-dialog-grid");
//        grid.setHgap(12);
//        grid.setVgap(12);
//        grid.setPadding(new Insets(18, 18, 12, 18));
//
//        TextField urlField = new TextField();
//        urlField.setPromptText("Paste URL...");
//        urlField.getStyleClass().add("gx-input");
//
//        ComboBox<String> modeCombo = new ComboBox<>();
//        modeCombo.getItems().addAll("Video (default)", "Audio", "Auto");
//        modeCombo.getSelectionModel().select(0);
//        modeCombo.getStyleClass().add("gx-combo");
//
//        ComboBox<String> qualityCombo = new ComboBox<>();
//        qualityCombo.getItems().addAll("Auto (recommended)", "240p", "360p", "480p", "720p", "1080p");
//        qualityCombo.getSelectionModel().select(0);
//        qualityCombo.getStyleClass().add("gx-combo");
//
//        TextField folderField = new TextField(System.getProperty("user.home") + File.separator + "Downloads");
//        folderField.getStyleClass().add("gx-input");
//        folderField.setEditable(false);
//
//        Button browseBtn = new Button("Browse");
//        browseBtn.getStyleClass().addAll("gx-btn", "gx-btn-ghost");
//
//        browseBtn.setOnAction(e -> {
//            DirectoryChooser chooser = new DirectoryChooser();
//            chooser.setTitle("Select Download Folder");
//            File selected = chooser.showDialog(pane.getScene().getWindow());
//            if (selected != null) folderField.setText(selected.getAbsolutePath());
//        });
//
//        CheckBox startNow = new CheckBox("Start immediately");
//        startNow.setSelected(true);
//        startNow.getStyleClass().add("gx-check");
//
//        Label tip = new Label("Tip: Playlist URLs will be detected automatically. You can choose per-item quality later.");
//        tip.getStyleClass().add("gx-text-muted");
//        tip.setWrapText(true);
//
//        grid.add(new Label("URL"), 0, 0);
//        grid.add(urlField, 1, 0, 2, 1);
//
//        grid.add(new Label("Mode"), 0, 1);
//        grid.add(modeCombo, 1, 1);
//
//        grid.add(new Label("Quality"), 0, 2);
//        grid.add(qualityCombo, 1, 2);
//
//        grid.add(new Label("Folder"), 0, 3);
//        grid.add(folderField, 1, 3);
//        grid.add(browseBtn, 2, 3);
//
//        grid.add(startNow, 1, 4, 2, 1);
//        grid.add(tip, 1, 5, 2, 1);
//
//        pane.setContent(grid);
//
//        // ✅ Disable OK until URL present (اختياري بس احترافي)
//        Button okBtn = (Button) pane.lookupButton(addToQueue);
//        okBtn.disableProperty().bind(urlField.textProperty().isEmpty());
//
//        Optional<ButtonType> result = dialog.showAndWait();
//        if (result.isPresent() && result.get() == addToQueue) {
//            String url = urlField.getText().trim();
//            String mode = modeCombo.getValue();
//            String quality = qualityCombo.getValue();
//            String folder = folderField.getText();
//            boolean start = startNow.isSelected();
//
//            // TODO: هنا بتعمل add to queue
//            // addDownload(url, mode, quality, folder, start);
//        }
//    }

//    private void showAddLinkDialog() {
//        Dialog<AddLinkResult> dialog = new Dialog<>();
//        dialog.setTitle("Add Link");
//        dialog.setHeaderText(null);
//
//        DialogPane pane = dialog.getDialogPane();
//        pane.getStylesheets().addAll(
//                getClass().getResource("styles/theme-base.css").toExternalForm(),
//                getClass().getResource("styles/layout.css").toExternalForm(),
//                getClass().getResource("styles/buttons.css").toExternalForm()
//        );
//        pane.getStyleClass().add("gx-dialog");
//
//        // reuse same app stylesheets so dialog matches UI
//        try {
//            Scene scene = (root != null) ? root.getScene() : null;
//            if (scene != null) pane.getStylesheets().addAll(scene.getStylesheets());
//        } catch (Exception ignored) {}
//
//        ButtonType addToQueueBtn = new ButtonType("Add to Queue", ButtonBar.ButtonData.OK_DONE);
//        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
//        pane.getButtonTypes().setAll(addToQueueBtn, cancelBtn);
//
//        GridPane grid = new GridPane();
//        grid.getStyleClass().add("gx-dialog-grid");
//        grid.setHgap(10);
//        grid.setVgap(10);
//        grid.setPadding(new Insets(14));
//
//        TextField urlField = new TextField();
//        urlField.setPromptText("Paste a URL (YouTube, playlist, direct file, ...)");
//        urlField.getStyleClass().add("gx-input");
//
//        ComboBox<String> modeBox = new ComboBox<>();
//        modeBox.getItems().addAll("Video (default)", "Audio only");
//        modeBox.getSelectionModel().selectFirst();
//        modeBox.getStyleClass().add("gx-combo");
//
//        // (لاحقاً: بنعبيها بعد تحليل yt-dlp -F)
//        ComboBox<String> qualityBox = new ComboBox<>();
//        qualityBox.getItems().addAll("Auto (recommended)", "Ask me later");
//        qualityBox.getSelectionModel().selectFirst();
//        qualityBox.getStyleClass().add("gx-combo");
//
//        TextField folderField = new TextField();
//        folderField.setPromptText("Download folder");
//        folderField.getStyleClass().add("gx-input");
//        folderField.setEditable(false);
//
//        Button browseBtn = new Button("Browse");
//        browseBtn.getStyleClass().addAll("gx-btn", "gx-btn-ghost");
//
//        CheckBox startNow = new CheckBox("Start immediately");
//        startNow.setSelected(true);
//        startNow.getStyleClass().add("gx-check");
//
//        Label hint = new Label("Tip: Playlist URLs will be detected automatically. You can choose per-item quality later.");
//        hint.getStyleClass().add("gx-text-muted");
//        hint.setWrapText(true);
//
//        int r = 0;
//        grid.add(new Label("URL"), 0, r);
//        grid.add(urlField, 1, r);
//        GridPane.setHgrow(urlField, Priority.ALWAYS);
//
//        r++;
//        grid.add(new Label("Mode"), 0, r);
//        grid.add(modeBox, 1, r);
//        GridPane.setHgrow(modeBox, Priority.ALWAYS);
//
//        r++;
//        grid.add(new Label("Quality"), 0, r);
//        grid.add(qualityBox, 1, r);
//        GridPane.setHgrow(qualityBox, Priority.ALWAYS);
//
//        r++;
//        grid.add(new Label("Folder"), 0, r);
//        grid.add(folderField, 1, r);
//        grid.add(browseBtn, 2, r);
//        GridPane.setHgrow(folderField, Priority.ALWAYS);
//
//        r++;
//        grid.add(startNow, 1, r);
//
//        r++;
//        grid.add(hint, 1, r);
//        GridPane.setHgrow(hint, Priority.ALWAYS);
//
//        pane.setContent(grid);
//
//        // default folder
//        try {
//            File dl = new File(System.getProperty("user.home"), "Downloads");
//            folderField.setText(dl.exists() ? dl.getAbsolutePath() : System.getProperty("user.home"));
//        } catch (Exception ignored) {}
//
//        browseBtn.setOnAction(e -> {
//            DirectoryChooser chooser = new DirectoryChooser();
//            chooser.setTitle("Choose download folder");
//
//            try {
//                String current = folderField.getText();
//                if (current != null && !current.isBlank()) {
//                    File f = new File(current);
//                    if (f.exists() && f.isDirectory()) chooser.setInitialDirectory(f);
//                }
//            } catch (Exception ignored) {}
//
//            Stage owner = null;
//            try { owner = (Stage) pane.getScene().getWindow(); } catch (Exception ignored) {}
//
//            File picked = chooser.showDialog(owner);
//            if (picked != null) folderField.setText(picked.getAbsolutePath());
//        });
//
//        // validation
//        Node okBtn = pane.lookupButton(addToQueueBtn);
//        okBtn.setDisable(true);
//        urlField.textProperty().addListener((obs, oldV, newV) -> {
//            okBtn.setDisable(newV == null || newV.trim().isEmpty());
//        });
//
//        Platform.runLater(urlField::requestFocus);
//
//        dialog.setResultConverter(btn -> {
//            if (btn == addToQueueBtn) {
//                AddLinkResult res = new AddLinkResult();
//                res.url = urlField.getText().trim();
//                res.audioOnly = modeBox.getSelectionModel().getSelectedIndex() == 1;
//                res.quality = qualityBox.getSelectionModel().getSelectedItem();
//                res.folder = folderField.getText();
//                res.startNow = startNow.isSelected();
//                return res;
//            }
//            return null;
//        });
//
//        Optional<AddLinkResult> result = dialog.showAndWait();
//        result.ifPresent(res -> {
//            // For now: feedback only (later: analyze with yt-dlp, fill quality list, create task)
//            if (statusText != null) {
//                statusText.setText(res.audioOnly ? "Queued (Audio): " + shorten(res.url)
//                        : "Queued (Video): " + shorten(res.url));
//            }
//        });
//    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 46 ? s.substring(0, 43) + "..." : s;
    }

    private static class AddLinkResult {
        String url;
        boolean audioOnly;
        String quality;
        String folder;
        boolean startNow;
    }
}
