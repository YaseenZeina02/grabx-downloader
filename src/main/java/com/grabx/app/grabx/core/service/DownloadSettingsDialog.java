package com.grabx.app.grabx.core.service;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.util.Optional;

/** Sound preferences and the existing cancelled-part retention setting. */
public final class DownloadSettingsDialog {
    public record Settings(int retentionDays, boolean sounds, double volume) { }

    public Optional<Settings> show(Window owner, int retentionDays, SoundFeedbackService sounds) {
        Dialog<Settings> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("GrabX settings");
        dialog.setHeaderText("Download preferences");
        dialog.getDialogPane().getStyleClass().add("gx-settings");
        var theme = DownloadSettingsDialog.class.getResource("/com/grabx/app/grabx/styles/settings.css");
        if (theme != null) dialog.getDialogPane().getStylesheets().add(theme.toExternalForm());
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        Label soundTitle = new Label("Sound feedback");
        soundTitle.setStyle("-fx-font-weight: bold;");
        CheckBox enabled = new CheckBox("Play short sounds for download events");
        enabled.setSelected(sounds.isEnabled());
        Slider volume = new Slider(0, 100, sounds.volume() * 100);
        volume.setPrefWidth(230);
        volume.setAccessibleText("Sound volume");
        volume.disableProperty().bind(enabled.selectedProperty().not());
        Label level = new Label();
        level.textProperty().bind(volume.valueProperty().asString("%.0f%%"));
        HBox volumeRow = new HBox(10, new Label("Volume"), volume, level);
        ComboBox<SoundFeedbackService.Cue> cue = new ComboBox<>();
        cue.getItems().setAll(SoundFeedbackService.Cue.values());
        cue.setValue(SoundFeedbackService.Cue.COMPLETE);
        cue.setAccessibleText("Preview sound");
        Button preview = new Button("Preview");
        preview.disableProperty().bind(enabled.selectedProperty().not().or(volume.valueProperty().lessThanOrEqualTo(0)));
        preview.setOnAction(e -> sounds.preview(cue.getValue(), volume.getValue() / 100));
        Label hint = new Label("Start and resume sounds play when new data arrives.\nEvents close together share one quiet cue.");
        hint.setWrapText(true);

        Label cleanupTitle = new Label("Incomplete download cleanup");
        cleanupTitle.setStyle("-fx-font-weight: bold;");
        TextField days = new TextField(Integer.toString(retentionDays));
        days.setPrefColumnCount(4);
        days.setAccessibleText("Cancelled download retention days");
        Label error = new Label("Enter a whole number from 1 to 365.");
        error.setStyle("-fx-text-fill: #d34c4c;");
        var invalid = Bindings.createBooleanBinding(() -> !validDays(days.getText()), days.textProperty());
        error.visibleProperty().bind(invalid); error.managedProperty().bind(invalid);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(invalid);
        Label cleanupHint = new Label("Only cancelled download parts are cleaned up.\nPaused and failed downloads are kept until you remove them.");
        cleanupHint.setWrapText(true);
        VBox content = new VBox(12, soundTitle, enabled, volumeRow, new HBox(10, cue, preview), hint,
                new Separator(), cleanupTitle, new HBox(10, new Label("Keep cancelled parts for"), days, new Label("days")), error, cleanupHint);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        dialog.setResultConverter(button -> button == save
                ? new Settings(Integer.parseInt(days.getText().trim()), enabled.isSelected(), volume.getValue() / 100) : null);
        dialog.setOnHidden(e -> sounds.silence());
        return dialog.showAndWait();
    }

    static boolean validDays(String value) {
        try { int days = Integer.parseInt(value.trim()); return days >= 1 && days <= 365; }
        catch (RuntimeException e) { return false; }
    }
}
