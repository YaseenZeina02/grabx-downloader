package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.service.SoundFeedbackService;
import com.grabx.app.grabx.core.service.TransferPreferences;
import com.grabx.app.grabx.core.service.TaskPreferences;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** A draft-based settings workspace. Navigation never silently commits edits. */
public final class SettingsPage extends BorderPane {
    public record Values(String folder, boolean rememberFolder, long directRate, long mediaRate,
                         boolean sounds, double volume, int retentionDays, int maxActive, boolean autoResume) {
        public Values(String folder, boolean rememberFolder, long directRate, long mediaRate,
                      boolean sounds, double volume, int retentionDays, int maxActive) {
            this(folder, rememberFolder, directRate, mediaRate, sounds, volume, retentionDays, maxActive, false);
        }
        public Values(String folder, boolean rememberFolder, long directRate, long mediaRate,
                      boolean sounds, double volume, int retentionDays) {
            this(folder, rememberFolder, directRate, mediaRate, sounds, volume, retentionDays, TaskPreferences.DEFAULT_LIMIT);
        }
    }
    private Values saved;
    private final Consumer<Values> apply;
    private final SoundFeedbackService sound;
    private final TextField folder = new TextField();
    private final CheckBox remember = new CheckBox("Use the last folder I selected");
    private final RateEditor direct = new RateEditor("direct"), media = new RateEditor("media");
    private final CheckBox sounds = new CheckBox("Play download event sounds");
    private final Slider volume = new Slider(0, 100, 30);
    private final TextField days = new TextField();
    private final ComboBox<Integer> maxActive = new ComboBox<>();
    private final CheckBox autoResume = new CheckBox("Resume interrupted downloads when GrabX opens");
    private final Label notice = new Label();
    private final Label dirtyStatus = new Label();
    private final Button save = new Button("Save changes");
    private final BorderPane sectionHost = new BorderPane();
    private final ToggleGroup navigation = new ToggleGroup();
    private boolean loading;

    public SettingsPage(Values initial, SoundFeedbackService sound, Consumer<Values> apply) {
        this.saved = initial; this.sound = sound; this.apply = apply;
        getStyleClass().addAll("gx-settings", "gx-settings-page");
        getStylesheets().add(SettingsPage.class.getResource("/com/grabx/app/grabx/styles/settings.css").toExternalForm());
        setPadding(new Insets(8, 12, 12, 12));
        setId("settings-page");
        setMinSize(0, 0); sectionHost.setMinSize(0, 0);
        VBox nav = new VBox(8, label("PREFERENCES", "settings-eyebrow"), label("Settings", "settings-nav-title"));
        nav.getStyleClass().add("settings-navigation");
        nav.setMinWidth(0); nav.setPrefWidth(190);
        nav.getChildren().addAll(
                navigation("Downloads", "Folders & destinations", downloads(), true),
                navigation("Tasks", "Queue & startup", tasks(), false),
                navigation("Network", "Download speed limits", network(), false),
                navigation("Sounds", "Volume & event previews", audio(), false),
                navigation("Storage", "Incomplete file cleanup", storage(), false));
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        Label hint = label("Your downloads keep running while you adjust settings.", "settings-muted"); hint.setWrapText(true);
        nav.getChildren().addAll(spacer, hint);
        ScrollPane navScroll = new ScrollPane(nav);
        navScroll.setFitToWidth(true); navScroll.setFitToHeight(true);
        navScroll.setMinWidth(190); navScroll.setPrefWidth(190); navScroll.setMaxWidth(190);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.getStyleClass().add("settings-nav-scroll");
        setLeft(navScroll); setCenter(sectionHost); BorderPane.setMargin(sectionHost, new Insets(0, 0, 0, 16));
        Button reset = new Button("Discard changes"); reset.setId("settings-discard");
        reset.setOnAction(e -> load(saved));
        save.setId("settings-save"); save.getStyleClass().add("settings-primary"); save.setOnAction(e -> saveChanges());
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        HBox actions = new HBox(12, dirtyStatus, gap, reset, save); actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("settings-actions");
        notice.setWrapText(true); notice.getStyleClass().add("settings-error"); notice.setId("settings-error");
        notice.managedProperty().bind(notice.visibleProperty()); notice.setVisible(false);
        VBox footer = new VBox(6, notice, actions); footer.setPadding(new Insets(10, 0, 0, 206)); setBottom(footer);
        InvalidationListener edited = ignored -> changed();
        folder.textProperty().addListener(edited); remember.selectedProperty().addListener(edited);
        direct.listen(edited); media.listen(edited); sounds.selectedProperty().addListener(edited);
        volume.valueProperty().addListener(edited); days.textProperty().addListener(edited);
        maxActive.valueProperty().addListener(edited);
        autoResume.selectedProperty().addListener(edited);
        load(initial);
        reset.disableProperty().bind(save.disableProperty());
    }
    private ToggleButton navigation(String title, String subtitle, Node content, boolean selected) {
        ToggleButton button = new ToggleButton(); button.setId("settings-nav-" + title.toLowerCase());
        button.setGraphic(new VBox(2, label(title, "settings-nav-label"), label(subtitle, "settings-nav-caption")));
        button.setMaxWidth(Double.MAX_VALUE); button.setToggleGroup(navigation);
        button.getStyleClass().add("settings-nav-item");
        button.setOnAction(e -> { button.setSelected(true); sectionHost.setCenter(content); });
        if (selected) { button.setSelected(true); sectionHost.setCenter(content); }
        return button;
    }
    private Node downloads() {
        folder.setId("settings-folder"); folder.setAccessibleText("Default download folder"); folder.setPromptText("Choose a download folder");
        HBox.setHgrow(folder, Priority.ALWAYS);
        Button browse = new Button("Browse…"); browse.setOnAction(e -> chooseFolder());
        HBox picker = new HBox(10, folder, browse); picker.setAlignment(Pos.CENTER_LEFT);
        remember.setId("settings-remember-folder");
        return section("Downloads", "A familiar home for every file.",
                card("Default download folder", "Used for new downloads. Existing tasks stay in their original folders.", picker),
                card("Folder selection", "When enabled, the last selected folder takes priority over the default folder.", remember),
                note("You can still choose a different destination for each download."));
    }
    private Node tasks() {
        autoResume.setId("settings-auto-resume"); autoResume.setWrapText(true);
        maxActive.setId("settings-max-active"); maxActive.setAccessibleText("Maximum active downloads");
        for (int count = 1; count <= TaskPreferences.MAX_LIMIT; count++) maxActive.getItems().add(count);
        maxActive.setPrefWidth(100);
        HBox choice = new HBox(12, maxActive, new Label("downloads at a time")); choice.setAlignment(Pos.CENTER_LEFT);
        return section("Tasks", "A clear queue. A comfortable pace.",
                card("Maximum active downloads", "Applies to direct files, video, audio and playlists together. Preparing, checking saved data and finishing a file count as active tasks.", choice),
                card("Automatic queue", "When all slots are busy, new requests wait in submission order. The next eligible task starts when a slot becomes free.",
                        note("Pausing a task frees its slot. Resuming puts it back in the queue. Pause all also pauses waiting tasks.")),
                card("On startup", "Continue tasks that were running or waiting when GrabX closed. Manually paused, failed and cancelled tasks stay stopped. Saved data is checked as usual before resuming.", autoResume),
                note("Startup resume is off by default and uses the same task limit. Changes apply the next time GrabX opens. Increasing the limit can start waiting tasks after Save; lowering it leaves current tasks running."));
    }
    private Node network() {
        return section("Network", "Leave room for browsing, calls and everything else.",
                card("Direct downloads · combined limit", "Shared across all direct downloads and their connections. Includes checking saved data before resume. Changes apply to running transfers.", direct),
                card("Video & audio · per-download limit", "Each video or audio task gets this limit. Changes apply when a task starts or restarts after Pause. Multiple tasks can use more in total.", media),
                note("Leave Limit speed off for unlimited speed. These are separate limits, not one app-wide ceiling. Some live streams may not support a limit. Short speed fluctuations are normal."));
    }
    private Node audio() {
        sounds.setId("settings-sounds"); volume.setId("settings-volume"); volume.setAccessibleText("Sound volume percent"); volume.setBlockIncrement(5);
        volume.disableProperty().bind(sounds.selectedProperty().not());
        Label percent = new Label(); percent.textProperty().bind(volume.valueProperty().asString("%.0f%%"));
        percent.setMinWidth(42); HBox.setHgrow(volume, Priority.ALWAYS);
        HBox volumeRow = new HBox(14, volume, percent); volumeRow.setAlignment(Pos.CENTER_LEFT);
        FlowPane previews = new FlowPane(10, 10);
        for (var cue : SoundFeedbackService.Cue.values()) {
            Button preview = new Button("▷  " + cue); preview.setPrefWidth(132);
            preview.setId("settings-preview-" + cue.name().toLowerCase());
            preview.disableProperty().bind(sounds.selectedProperty().not().or(volume.valueProperty().lessThanOrEqualTo(0)));
            preview.setOnAction(e -> sound.preview(cue, volume.getValue() / 100));
            previews.getChildren().add(preview);
        }
        return section("Sounds", "Small cues. Clear feedback. Your volume.",
                card("Sound feedback", "Short sounds for start, resume, pause, cancel, completion and errors.", sounds),
                card("Volume", "Preview uses this volume. Save to apply it to download events.", volumeRow),
                card("Try each sound", "Original, locally generated tones. Start and resume play when new file data arrives.", previews));
    }
    private Node storage() {
        days.setId("settings-retention"); days.setAccessibleText("Keep cancelled parts for days"); days.setMaxWidth(100);
        HBox period = new HBox(10, days, new Label("days")); period.setAlignment(Pos.CENTER_LEFT);
        return section("Storage", "Keep useful progress. Clean up cancelled files.",
                card("Keep cancelled download parts", "Choose between 1 and 365 days before cancelled partial files are eligible for automatic cleanup.", period),
                note("Paused and failed downloads keep their saved data until you explicitly remove them. Completed files are not deleted by this setting."));
    }
    private Node section(String title, String subtitle, Node... cards) {
        VBox body = new VBox(16, label(title, "settings-heading"), label(subtitle, "settings-muted"));
        body.getChildren().addAll(cards); body.setPadding(new Insets(22, 24, 24, 24)); body.setFillWidth(true);
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("settings-scroll");
        return scroll;
    }
    private static VBox card(String title, String description, Node control) {
        Label detail = label(description, "settings-muted"); detail.setWrapText(true);
        VBox box = new VBox(12, label(title, "settings-card-title"), detail, control);
        box.getStyleClass().add("settings-card"); return box;
    }
    private static Label label(String value, String style) { Label label = new Label(value); label.getStyleClass().add(style); return label; }
    private static Node note(String text) { Label label = label(text, "settings-note"); label.setWrapText(true); label.setMaxWidth(Double.MAX_VALUE); return label; }
    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Default download folder");
        try { Path path = Path.of(folder.getText()); if (Files.isDirectory(path)) chooser.setInitialDirectory(path.toFile()); }
        catch (RuntimeException ignored) { }
        var selected = chooser.showDialog(getScene().getWindow());
        if (selected != null) folder.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }
    private Values readValues() {
        String text = folder.getText().trim();
        if (text.isBlank() || !Path.of(text).isAbsolute()) throw new IllegalArgumentException("Choose an absolute download folder in Downloads.");
        int retention;
        try { retention = Integer.parseInt(days.getText().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Storage: enter a whole number from 1 to 365 days."); }
        if (retention < 1 || retention > 365) throw new IllegalArgumentException("Storage: enter a whole number from 1 to 365 days.");
        Integer maximum = maxActive.getValue();
        if (maximum == null || maximum < 1 || maximum > TaskPreferences.MAX_LIMIT)
            throw new IllegalArgumentException("Tasks: choose between 1 and 16 active downloads.");
        return new Values(Path.of(text).normalize().toString(), remember.isSelected(), direct.rate(), media.rate(),
                sounds.isSelected(), volume.getValue() / 100.0, retention, maximum, autoResume.isSelected());
    }
    public boolean isDirty() {
        try {
            Values current = readValues();
            // Slider percent conversion must not mark an untouched, previously saved
            // fractional volume as dirty due to floating-point roundoff.
            if (Math.abs(current.volume() - saved.volume()) < 1e-9)
                current = new Values(current.folder(), current.rememberFolder(), current.directRate(), current.mediaRate(),
                        current.sounds(), saved.volume(), current.retentionDays(), current.maxActive(), current.autoResume());
            return !saved.equals(current);
        } catch (RuntimeException e) { return true; }
    }
    private void changed() {
        if (loading) return;
        boolean dirty = isDirty(); save.setDisable(!dirty);
        dirtyStatus.setText(dirty ? "Unsaved changes" : "All changes saved");
        notice.setVisible(false);
    }
    private void load(Values value) {
        loading = true;
        folder.setText(value.folder()); remember.setSelected(value.rememberFolder());
        direct.load(value.directRate()); media.load(value.mediaRate()); maxActive.setValue(value.maxActive());
        autoResume.setSelected(value.autoResume());
        sounds.setSelected(value.sounds()); volume.setValue(value.volume() * 100); days.setText(Integer.toString(value.retentionDays()));
        loading = false; changed(); sound.silence();
    }
    public boolean saveChanges() {
        try {
            Values value = readValues();
            // Revalidate destinations only when the folder actually changes; unavailable
            // removable drives must not prevent users changing volume or bandwidth.
            if (!value.folder().equals(saved.folder())) {
                Path target = Path.of(value.folder());
                if (!Files.isDirectory(target) || !Files.isWritable(target))
                    throw new IllegalArgumentException("Downloads: choose an existing writable folder.");
            }
            apply.accept(value); saved = value; changed(); return true;
        } catch (RuntimeException e) {
            notice.setText(e instanceof IllegalArgumentException ? e.getMessage() : "Could not save settings. Please try again.");
            notice.setVisible(true); return false;
        }
    }
    /** Dismissal, Keep editing, and invalid Save all retain the draft. */
    public boolean confirmLeave(Window owner) {
        if (!isDirty()) { sound.silence(); return true; }
        ButtonType saveChoice = new ButtonType("Save changes", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        ButtonType keepEditing = new ButtonType("Keep editing", ButtonBar.ButtonData.CANCEL_CLOSE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner); dialog.setTitle("GrabX · Unsaved changes");
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("gx-settings", "settings-confirm");
        pane.getStylesheets().addAll(getStylesheets());
        pane.setPrefWidth(540);

        javafx.scene.shape.SVGPath pencil = new javafx.scene.shape.SVGPath();
        pencil.setContent("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 0 0 0-1.41l-2.34-2.34a.996.996 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z");
        pencil.getStyleClass().add("settings-confirm-pencil");
        StackPane icon = new StackPane(pencil); icon.getStyleClass().add("settings-confirm-icon");
        icon.setMinSize(48, 48); icon.setMaxSize(48, 48);
        VBox heading = new VBox(4, label("UNSAVED CHANGES", "settings-confirm-eyebrow"),
                label("Save your changes?", "settings-confirm-title"));
        HBox title = new HBox(16, icon, heading); title.setAlignment(Pos.CENTER_LEFT);
        Label description = label("Save your settings before leaving, or discard your changes to keep the previous settings.", "settings-confirm-description");
        description.setWrapText(true); description.setPrefWidth(492);
        description.setMinHeight(Region.USE_PREF_SIZE); description.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(18, title, description);
        pane.setContent(content);
        pane.getButtonTypes().setAll(discard, keepEditing, saveChoice);
        Button saveButton = (Button) pane.lookupButton(saveChoice);
        Button editButton = (Button) pane.lookupButton(keepEditing);
        Button discardButton = (Button) pane.lookupButton(discard);
        saveButton.getStyleClass().add("settings-confirm-save");
        editButton.getStyleClass().add("settings-confirm-edit");
        discardButton.getStyleClass().add("settings-confirm-discard");
        ButtonBar.setButtonUniformSize(discardButton, false);
        ((ButtonBar) pane.lookup(".button-bar")).setButtonOrder("N+CY");
        // Keep the non-destructive keyboard default, independently of visual emphasis.
        saveButton.setDefaultButton(false); editButton.setDefaultButton(true);
        dialog.setOnShown(e -> editButton.requestFocus());
        dialog.setResultConverter(button -> button);
        ButtonType choice = dialog.showAndWait().orElse(keepEditing);
        boolean leave = choice == discard || (choice == saveChoice && saveChanges());
        if (leave) sound.silence();
        return leave;
    }
    private static final class RateEditor extends HBox {
        final CheckBox enabled = new CheckBox("Limit speed");
        final TextField value = new TextField("2");
        final ComboBox<String> unit = new ComboBox<>();
        final String name;
        RateEditor(String name) {
            super(12); this.name = name; setAlignment(Pos.CENTER_LEFT);
            enabled.setId("settings-" + name + "-enabled"); value.setId("settings-" + name + "-rate"); unit.setId("settings-" + name + "-unit");
            value.setAccessibleText(name + " download limit"); unit.setAccessibleText(name + " speed unit");
            value.setPrefWidth(90); value.setMaxWidth(110); unit.getItems().setAll("KB/s", "MB/s"); unit.setValue("MB/s");
            value.disableProperty().bind(enabled.selectedProperty().not()); unit.disableProperty().bind(enabled.selectedProperty().not());
            getChildren().addAll(enabled, value, unit);
        }
        long rate() {
            if (!enabled.isSelected()) return 0;
            try {
                BigDecimal amount = new BigDecimal(value.getText().trim());
                long bytes = amount.multiply(BigDecimal.valueOf("MB/s".equals(unit.getValue()) ? 1_000_000 : 1_000)).longValueExact();
                if (bytes < 1_000 || bytes > TransferPreferences.MAX_RATE) throw new IllegalArgumentException();
                return bytes;
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Network: " + name + " limit must be between 1 KB/s and 1,000 MB/s.");
            }
        }
        void load(long rate) {
            enabled.setSelected(rate > 0);
            long divisor = rate > 0 && rate < 1_000_000 ? 1_000 : 1_000_000;
            unit.setValue(divisor == 1_000 ? "KB/s" : "MB/s");
            value.setText(rate == 0 ? "2" : BigDecimal.valueOf(rate).divide(BigDecimal.valueOf(divisor)).stripTrailingZeros().toPlainString());
        }
        void listen(InvalidationListener listener) {
            enabled.selectedProperty().addListener(listener); value.textProperty().addListener(listener); unit.valueProperty().addListener(listener);
        }
    }
}
