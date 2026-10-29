package com.grabx.app.grabx.setup;

import javafx.application.Platform;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.awt.Desktop;
import java.nio.file.Path;

/** Available after installation without restarting the app or resetting preferences. */
public final class BrowserIntegrationPane extends VBox {
    private final ComboBox<BrowserSetupService.Browser> browser = new ComboBox<>();
    private final TextField id = new TextField(), location = new TextField();
    private final Label status = text("");
    private final Button prepare = new Button("Prepare extension files"), connect = new Button("Connect browser");
    private String language = "en";
    private boolean busy;
    private java.util.function.Consumer<Boolean> connectionChanged;
    private Path prepared;
    private final java.util.concurrent.Callable<Path> prepareFiles;
    private final Connector connector;
    @FunctionalInterface public interface Connector { void register(BrowserSetupService.Browser browser, String id) throws Exception; }

    public BrowserIntegrationPane() { this(ignored -> {}); }
    public BrowserIntegrationPane(java.util.function.Consumer<Boolean> linked) {
        this(linked, () -> ExtensionBundleService.forCurrentUser().prepare(), BrowserSetupService::register);
    }
    public BrowserIntegrationPane(java.util.function.Consumer<Boolean> linked,
                                  java.util.concurrent.Callable<Path> prepareFiles, Connector connector) {
        super(12); connectionChanged = linked; setId("browser-integration");
        this.prepareFiles = prepareFiles; this.connector = connector;
        browser.getItems().setAll(BrowserSetupService.Browser.values()); browser.getSelectionModel().selectFirst();
        browser.setId("browser-connect-browser"); browser.setMaxWidth(Double.MAX_VALUE);
        id.setId("browser-connect-id"); id.setPromptText("32-character extension ID");
        id.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        location.setEditable(false); location.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        location.setId("browser-extension-folder"); location.setVisible(false); location.setManaged(false);
        prepare.setId("browser-prepare"); connect.setId("browser-connect");
        prepare.setOnAction(e -> prepare()); connect.setOnAction(e -> connect());
        Button openFolder = new Button("Open extension folder");
        openFolder.setOnAction(e -> { if (prepared != null) desktop(() -> Desktop.getDesktop().open(prepared.toFile())); });
        openFolder.disableProperty().bind(location.textProperty().isEmpty());
        Button copy = new Button("Copy folder path"); copy.disableProperty().bind(location.textProperty().isEmpty());
        copy.setOnAction(e -> { var value = new javafx.scene.input.ClipboardContent(); value.putString(location.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(value); });
        Button extensions = new Button("Copy browser extensions address");
        extensions.setOnAction(e -> {
            // Browser-internal URLs must be opened in the selected browser by the user.
            var value = new javafx.scene.input.ClipboardContent(); value.putString(browser.getValue().extensions);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(value);
            status.setText(t("Extensions page address copied. Paste it in the selected browser."));
        });
        browser.valueProperty().addListener((o,a,b) -> { status.setText(t("")); linked.accept(false); update(); });
        id.textProperty().addListener((o,a,b) -> { status.setText(t("")); linked.accept(false); update(); });
        getChildren().addAll(text("1. Prepare the included extension files"),
                text("GrabX includes the extension. Prepare its permanent folder on this device first."),
                prepare, location, new javafx.scene.layout.FlowPane(8,8,openFolder,copy),
                text("2. Add the extension to your browser"), browser, extensions,
                text("Enable Developer mode, click Load unpacked and select the prepared chromium folder. Keep this folder in place."),
                text("3. Connect after adding the extension"),
                text("Copy the ID shown under GrabX on the browser extensions page. Paste it below and connect."), id, connect,
                text("Connecting applies immediately, independently of Save changes. Reload the extension to check its connection status."), status);
        update();
    }
    private static Label text(String message) { Label label = new Label(message); label.setWrapText(true); label.setMaxWidth(Double.MAX_VALUE); return label; }
    public void setLanguage(String code) {
        language = code;
        translate(this);
    }
    private String t(String source) { return com.grabx.app.grabx.util.I18n.text(source, language); }
    private void translate(javafx.scene.Node node) {
        if (node instanceof Labeled label && label != status) {
            String source = (String) label.getProperties().computeIfAbsent("browser.source", ignored -> label.getText());
            label.setText(t(source));
        }
        if (node instanceof javafx.scene.Parent parent) parent.getChildrenUnmodifiable().forEach(this::translate);
    }
    private void update() {
        id.setVisible(prepared != null); id.setManaged(prepared != null);
        connect.setVisible(prepared != null); connect.setManaged(prepared != null);
        prepare.setDisable(busy); connect.setDisable(busy || prepared == null || !BrowserSetupService.validId(id.getText().trim())); browser.setDisable(busy); id.setDisable(busy); }
    private void prepare() {
        run(() -> prepared = prepareFiles.call(), () -> {
            location.setText(prepared.toString()); location.setVisible(true); location.setManaged(true);
            status.setText(t("Extension files are ready. Add the extension in your browser, then return here to connect."));
        });
    }
    private void connect() {
        var chosen = browser.getValue(); var extensionId = id.getText().trim();
        run(() -> connector.register(chosen, extensionId), () -> { connectionChanged.accept(true); status.setText(t("Browser connection registered. Reload the extension and check its connection status.")); });
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void desktop(Work action) { try { action.run(); } catch (Exception e) { status.setText(e.getMessage()); } }
    private void run(Work work, Runnable success) {
        if (busy) return; busy = true; update(); status.setText(t("Preparing…"));
        Thread thread = new Thread(() -> {
            try { work.run(); Platform.runLater(() -> { busy = false; success.run(); update(); }); }
            catch (Exception e) { Platform.runLater(() -> { busy = false; status.setText(e.getMessage()); update(); }); }
        }, "grabx-browser-settings"); thread.setDaemon(true); thread.start();
    }
}
