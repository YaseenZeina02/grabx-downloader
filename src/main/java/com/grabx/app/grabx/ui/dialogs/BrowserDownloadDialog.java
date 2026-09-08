package com.grabx.app.grabx.ui.dialogs;

import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Compact confirmation before choosing a destination for a browser download. */
public final class BrowserDownloadDialog {
    public static boolean show(Window owner, String filename) {
        Dialog<Boolean> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Download with GrabX");
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("browser-download-dialog");
        pane.getStylesheets().add(BrowserDownloadDialog.class.getResource(
                "/com/grabx/app/grabx/styles/browser-download-dialog.css").toExternalForm());
        pane.setPrefWidth(490);
        pane.setMaxWidth(490);

        Label heading = label("Ready to download", "download-heading");
        Label name = label(filename, "download-filename");
        name.setWrapText(true);
        name.setMaxWidth(438);
        name.setMaxHeight(64);
        name.setTooltip(new Tooltip(filename));
        Label hint = label("Choose where to save this file.", "download-hint");
        hint.setWrapText(true);
        pane.setContent(new VBox(14, heading, name, hint));
        ButtonType proceed = new ButtonType("Choose folder", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, proceed);
        Button primary = (Button) pane.lookupButton(proceed);
        primary.getStyleClass().add("download-primary");
        // Let the complete label determine the minimum button width.
        primary.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        primary.setPrefWidth(160);
        primary.setTextOverrun(OverrunStyle.CLIP);
        dialog.setResultConverter(button -> button == proceed);
        return dialog.showAndWait().orElse(false);
    }
    private static Label label(String text, String style) {
        Label label = new Label(text); label.getStyleClass().add(style); return label;
    }
}
