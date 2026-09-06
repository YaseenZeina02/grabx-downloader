package com.grabx.app.grabx.ui.dialogs;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.util.List;
import java.util.Optional;

/** A compact choice between a new file and an explicitly selected saved download. */
public final class BrowserDownloadDialog {
    public record Choice(DownloadRow resume) { }
    public static Optional<Choice> show(Window owner, String filename, List<DownloadRow> candidates) {
        Dialog<Choice> dialog = new Dialog<>();
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
        ToggleGroup group = new ToggleGroup();
        RadioButton fresh = new RadioButton("Download a new file");
        RadioButton resume = new RadioButton("Continue a previous download");
        fresh.setToggleGroup(group); resume.setToggleGroup(group); fresh.setSelected(true);
        Label freshHint = label("Choose a folder, then start downloading.", "download-hint");
        Label resumeHint = label("Choose the same file from your unfinished downloads.", "download-hint");
        resumeHint.setWrapText(true);
        ComboBox<DownloadRow> saved = new ComboBox<>();
        saved.getItems().setAll(candidates);
        saved.getSelectionModel().selectFirst();
        saved.setMaxWidth(Double.MAX_VALUE);
        saved.setVisibleRowCount(4);
        saved.setCellFactory(list -> rowCell());
        saved.setButtonCell(rowCell());
        saved.disableProperty().bind(resume.selectedProperty().not());
        saved.setAccessibleText("Previous download to continue");
        Label checkHint = label("We’ll check that it’s the same file before continuing.", "download-hint");
        checkHint.setWrapText(true);
        VBox newCard = new VBox(7, fresh, freshHint);
        VBox resumeCard = new VBox(9, resume, resumeHint, saved, checkHint);
        newCard.getStyleClass().add("download-choice");
        resumeCard.getStyleClass().add("download-choice");
        var selected = javafx.css.PseudoClass.getPseudoClass("selected");
        newCard.pseudoClassStateChanged(selected, true);
        group.selectedToggleProperty().addListener((obs, old, value) -> {
            newCard.pseudoClassStateChanged(selected, fresh.isSelected());
            resumeCard.pseudoClassStateChanged(selected, resume.isSelected());
        });
        pane.setContent(new VBox(14, heading, name, newCard, resumeCard));
        ButtonType proceed = new ButtonType("Choose folder", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, proceed);
        Button primary = (Button) pane.lookupButton(proceed);
        primary.getStyleClass().add("download-primary");
        resume.selectedProperty().addListener((obs, old, value) -> primary.setText(value ? "Check & continue" : "Choose folder"));
        primary.disableProperty().bind(resume.selectedProperty().and(saved.valueProperty().isNull()));
        dialog.setResultConverter(button -> button == proceed ? new Choice(resume.isSelected() ? saved.getValue() : null) : null);
        return dialog.showAndWait();
    }
    private static Label label(String text, String style) {
        Label label = new Label(text); label.getStyleClass().add(style); return label;
    }
    private static ListCell<DownloadRow> rowCell() {
        return new ListCell<>() {
            @Override protected void updateItem(DownloadRow item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title.get());
                setTooltip(empty || item == null ? null : new Tooltip(item.title.get()));
            }
        };
    }
}
