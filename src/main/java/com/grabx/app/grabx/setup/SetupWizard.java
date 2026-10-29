package com.grabx.app.grabx.setup;

import com.grabx.app.grabx.core.service.*;
import com.grabx.app.grabx.util.SetupDiagnostics;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

/** First-run setup. Draft preferences are committed only by Start GrabX. */
public final class SetupWizard {
    private final Stage stage;
    private final Preferences store;
    private final Runnable finished;
    private final SetupPreferences setup;
    private final BorderPane root = new BorderPane();
    private final VBox content = new VBox(16), navigation = new VBox(12);
    private final Label error = new Label();
    private final Button back = new Button(), next = new Button();
    private final ComboBox<LanguagePreferences.Language> language = new ComboBox<>();
    private final TextField folder = new TextField();
    private final Label folderError = new Label(), folderHint = new Label();
    private final ScrollPane scroll = new ScrollPane(content);
    private final CheckBox remember = new CheckBox(), sounds = new CheckBox();
    private final IntegerProperty simultaneous = new SimpleIntegerProperty(3);
    private BrowserIntegrationPane browserPane;
    private List<SetupDiagnostics.Check> checks;
    private boolean checking, linked;
    private int step;

    public SetupWizard(Stage stage, Preferences store, Runnable finished) {
        this.stage = stage; this.store = store; this.finished = finished;
        setup = new SetupPreferences(store);
        language.getItems().setAll(LanguagePreferences.LANGUAGES);
        language.setValue(LanguagePreferences.LANGUAGES.stream().filter(l -> l.code().equals(new LanguagePreferences(store).code())).findFirst().orElseThrow());
        folder.setText(setup.initialFolder());
        folder.setId("setup-folder"); folder.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        folderError.setId("setup-folder-error"); folderError.getStyleClass().add("setup-error"); folderError.setWrapText(true);
        folderError.managedProperty().bind(folderError.visibleProperty()); folderError.setVisible(false);
        folderHint.getStyleClass().add("setup-muted"); folderHint.setWrapText(true);
        folderHint.managedProperty().bind(folderHint.visibleProperty());
        folder.textProperty().addListener((o, a, b) -> { clearFolderError(); updateFolderHint(); });
        remember.setSelected(new DownloadFolderPreferences(store).remembersLastFolder());
        sounds.setSelected(store.getBoolean("grabx.sounds.enabled", true));
        simultaneous.set(new TaskPreferences(store).maxActive());
        styleCombo(language, "setup-language", 240);
        language.valueProperty().addListener((o, a, b) -> render());
        root.getStyleClass().add("setup-root"); root.setMinSize(0, 0);
        navigation.getStyleClass().add("setup-sidebar"); navigation.setPrefWidth(238);
        scroll.setFitToWidth(true); scroll.getStyleClass().add("setup-scroll");
        scroll.setMinSize(0, 0);
        ScrollPane sidebar = new ScrollPane(navigation);
        sidebar.setFitToWidth(true); sidebar.setFitToHeight(true); sidebar.setMinHeight(0);
        sidebar.setPrefWidth(238); sidebar.setMinWidth(180);
        sidebar.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebar.getStyleClass().add("setup-scroll");
        content.setPadding(new Insets(36)); root.setLeft(sidebar); root.setCenter(scroll);
        back.setOnAction(e -> { step--; render(); });
        next.setId("setup-next"); next.getStyleClass().add("setup-primary"); next.setOnAction(e -> advance());
        error.getStyleClass().add("setup-error"); error.setWrapText(true);
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        HBox actions = new HBox(14, back, gap, next); actions.setAlignment(Pos.CENTER_LEFT);
        VBox footer = new VBox(8, error, actions); footer.getStyleClass().add("setup-footer"); root.setBottom(footer);
    }
    private boolean ar() { return language.getValue().code().equals("ar"); }
    private <T> void styleCombo(ComboBox<T> combo, String id, double width) {
        combo.setId(id); combo.setPrefWidth(width); combo.setMinHeight(44);
        combo.setCellFactory(view -> comboCell()); combo.setButtonCell(comboCell());
        combo.setOnShowing(e -> {
            if (combo.getSkin() instanceof javafx.scene.control.skin.ComboBoxListViewSkin<?> skin
                    && skin.getPopupContent() instanceof ListView<?> list) {
                list.setFixedCellSize(42);
                list.setPrefHeight(Math.min(8, combo.getItems().size()) * 42 + 14);
            }
        });
    }
    private <T> ListCell<T> comboCell() {
        return new ListCell<>() {
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty); setText(empty || item == null ? null : item.toString());
            }
        };
    }
    private String t(String en, String ar) { return ar() ? ar : en; }
    public Scene scene() {
        Scene scene = new Scene(root, 1060, 740);
        scene.getStylesheets().add(SetupWizard.class.getResource("/com/grabx/app/grabx/styles/setup.css").toExternalForm());
        render(); return scene;
    }
    public void show() {
        com.grabx.app.grabx.util.AppBranding.apply(stage, true);
        stage.setTitle("GrabX · Setup"); stage.setMinWidth(880); stage.setMinHeight(650);
        stage.setScene(scene()); com.grabx.app.grabx.util.WindowBounds.show(stage, 1060, 740, 880, 500);
    }
    private Label label(String value, String style) {
        Label l = new Label(value); l.setWrapText(true); l.getStyleClass().add(style); l.setMaxWidth(Double.MAX_VALUE); return l;
    }
    private VBox card(Node... nodes) { VBox box = new VBox(12, nodes); box.getStyleClass().add("setup-card"); return box; }
    private Button button(String title, Runnable action) { Button b = new Button(title); b.setOnAction(e -> action.run()); return b; }
    private void heading(String title, String description) {
        content.getChildren().addAll(label(title, "setup-title"), label(description, "setup-muted"));
    }
    private void render() {
        root.setNodeOrientation(ar() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        content.getChildren().clear(); navigation.getChildren().clear(); error.setText("");
        navigation.getChildren().addAll(label("GX", "setup-mark"), label("GrabX", "setup-brand"), label(t("MAKE IT YOURS", "على ذوقك"), "setup-eyebrow"));
        String[] titles = ar() ? new String[]{"أهلًا في GrabX", "جاهزية الجهاز", "إعداداتك", "إضافة المتصفح", "جاهز للانطلاق"} :
                new String[]{"Welcome", "System check", "Your defaults", "Browser extension", "Ready to go"};
        for (int i = 0; i < titles.length; i++) {
            Label item = label((i < step ? "✓" : "0" + (i + 1)) + "   " + titles[i], "setup-step");
            if (i == step) item.getStyleClass().add("setup-step-active"); navigation.getChildren().add(item);
        }
        Region space = new Region(); VBox.setVgrow(space, Priority.ALWAYS);
        navigation.getChildren().addAll(space, label(System.getProperty("os.name") + " · " + System.getProperty("os.arch"), "setup-muted"), label(t("LOCAL EDITION", "نسخة محلية"), "setup-eyebrow"));
        back.setText(t("Back", "السابق")); back.setDisable(step == 0);
        next.setText(step == 4 ? t("Start GrabX", "ابدأ مع GrabX") : t("Continue", "متابعة"));
        next.setDisable(checking);
        switch (step) {
            case 0 -> welcome(); case 1 -> tools(); case 2 -> defaults(); case 3 -> browsers(); case 4 -> review();
        }
    }
    private void welcome() {
        heading(t("A better home for every download.", "كل تنزيل، بمكانه."),
                t("A few thoughtful defaults. Everything ready before your first link.", "خلّينا نضبط الأساسيات ونجهّز كل شيء قبل أول رابط."));
        content.getChildren().add(card(label(t("One setup. Your way.", "إعداد بسيط، بطريقتك."), "setup-card-title"),
                label(t("Check video tools, choose where files go, and connect your browser. You can change your preferences later in Settings.",
                        "نفحص أدوات الفيديو، نحدد مكان ملفاتك، ونربط المتصفح. بتقدر تعدّل خياراتك لاحقًا من الإعدادات."), "setup-muted")));
        content.getChildren().addAll(label(t("Setup language", "لغة الإعداد"), "setup-card-title"), language,
                label(t("English and Arabic setup guidance. The app offers six interface languages.", "إرشادات الإعداد بالعربية والإنجليزية. واجهة التطبيق بتدعم ست لغات."), "setup-muted"));
    }
    private void tools() {
        heading(t("Let's check your essentials.", "نتأكد إن جهازك جاهز."), t("Working tools are reused. Missing tools and extension files are prepared for this device; an internet connection may be needed.", "بنستخدم الأدوات الجاهزة وبنجهّز الناقص وملفات الإضافة حسب جهازك؛ ممكن نحتاج اتصال بالإنترنت."));
        if (checks == null) content.getChildren().add(label(t("Checking your device…", "جاري فحص الجهاز…"), "setup-muted"));
        else for (var check : checks) {
            VBox row = card(label((check.ready() ? "✓  " : "!  ") + check.name(), check.ready() ? "setup-ok" : "setup-warning"), label(check.detail(), "setup-muted"));
            if (!check.ready() && !check.helpUrl().isBlank()) row.getChildren().add(button(t("Installation instructions ↗", "تعليمات التثبيت ↗"), () -> open(check.helpUrl())));
            content.getChildren().add(row);
        }
        content.getChildren().addAll(button(t("Check & prepare again", "إعادة الفحص والتجهيز"), this::scan),
                label(t("Missing media tools limit YouTube, merging or conversion. Direct file downloads remain available. Continue to configure GrabX, or install the missing tool and check again.",
                        "نقص أدوات الوسائط بيحدّ من يوتيوب أو الدمج والتحويل. تنزيل الملفات المباشرة بيظل متاح. بتقدر تكمل الإعداد أو تثبّت الأداة الناقصة وتعيد الفحص."), "setup-muted"));
    }
    private void scan() {
        if (checking) return; checking = true; render();
        Thread worker = new Thread(() -> {
            try { checks = Boolean.getBoolean("grabx.packaged") ? com.grabx.app.grabx.util.DevicePreparation.prepare() : SetupDiagnostics.inspect(); }
            finally { Platform.runLater(() -> { checking = false; render(); }); }
        }, "grabx-setup-check"); worker.setDaemon(true); worker.start();
    }
    private void defaults() {
        heading(t("Set your everyday defaults.", "إعداداتك من أول يوم."), t("These are saved together when you finish setup.", "بنحفظ هالخيارات مع بعض لما تكمّل الإعداد."));
        Button browse = button(t("Choose folder", "اختيار مجلد"), () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            try { if (Files.isDirectory(Path.of(folder.getText()))) chooser.setInitialDirectory(Path.of(folder.getText()).toFile()); } catch (Exception ignored) {}
            var selected = chooser.showDialog(stage); if (selected != null) folder.setText(selected.getAbsolutePath());
        });
        remember.setText(t("Use my last download folder instead of the default", "استخدم آخر مجلد تحميل بدل الافتراضي"));
        sounds.setText(t("Play download event sounds", "تشغيل أصوات أحداث التنزيل"));
        updateFolderHint();
        content.getChildren().addAll(card(label(t("Default download folder", "مجلد التنزيل الافتراضي"), "setup-card-title"), folder, folderError, folderHint, browse, remember,
                        label(t("Leave this off for temporary folder changes in Add Link.", "خلّيه مطفّي حتى يكون تغيير المجلد في Add Link للتنزيل الحالي فقط."), "setup-muted")),
                card(label(t("App language", "لغة التطبيق"), "setup-card-title"), language,
                        label(t("Simultaneous downloads", "التنزيلات المتزامنة"), "setup-card-title"), concurrencyControl(), sounds));
    }
    private HBox concurrencyControl() {
        Button minus = button("−", () -> simultaneous.set(Math.max(1, simultaneous.get() - 1)));
        Button plus = button("+", () -> simultaneous.set(Math.min(TaskPreferences.MAX_LIMIT, simultaneous.get() + 1)));
        minus.setId("setup-concurrency-minus"); plus.setId("setup-concurrency-plus");
        minus.setAccessibleText(t("Fewer simultaneous downloads", "تقليل التنزيلات المتزامنة"));
        plus.setAccessibleText(t("More simultaneous downloads", "زيادة التنزيلات المتزامنة"));
        minus.disableProperty().bind(simultaneous.lessThanOrEqualTo(1));
        plus.disableProperty().bind(simultaneous.greaterThanOrEqualTo(TaskPreferences.MAX_LIMIT));
        Label value = new Label(); value.setId("setup-concurrency-value"); value.textProperty().bind(simultaneous.asString());
        value.getStyleClass().add("setup-stepper-value");
        HBox control = new HBox(0, minus, value, plus); control.setId("setup-simultaneous");
        control.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT); control.setAlignment(Pos.CENTER);
        control.setMaxWidth(Region.USE_PREF_SIZE); control.getStyleClass().add("setup-stepper");
        return control;
    }
    private void clearFolderError() {
        folderError.setVisible(false); folderError.setText("");
        folder.pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), false);
    }
    private void updateFolderHint() {
        boolean creates = false;
        try { creates = Path.of(folder.getText()).normalize().equals(setup.suggestedFolder()) && !Files.exists(setup.suggestedFolder()); }
        catch (Exception ignored) {}
        folderHint.setText(t("The GrabX folder will be created when you finish setup.", "رح ننشئ مجلد GrabX لما تكمّل الإعداد."));
        folderHint.setVisible(creates);
    }
    private boolean validateFolderStep() {
        try { setup.validateFolder(folder.getText()); clearFolderError(); return true; }
        catch (SetupPreferences.FolderValidationException e) {
            String message = switch (e.problem()) {
                case EMPTY -> t("Choose a download folder.", "اختار مجلد للتنزيلات.");
                case INVALID_PATH -> t("Enter a valid absolute folder path.", "اكتب مسار مجلد كامل وصحيح.");
                case NOT_FOUND -> t("This folder does not exist. Choose an existing folder.", "هذا المجلد مش موجود. اختار مجلد موجود أو صحّح المسار.");
                case NOT_DIRECTORY -> t("This path points to a file. Choose a folder.", "هذا المسار لملف، مش لمجلد. اختار مجلد للتنزيلات.");
                case NOT_WRITABLE -> t("GrabX cannot write to this folder. Choose another folder.", "GrabX ما بقدر يحفظ في هذا المجلد. اختار مجلد ثاني.");
            };
            folderError.setText(message); folderError.setVisible(true);
            folder.pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), true);
            folder.requestFocus(); scroll.setVvalue(0); return false;
        }
    }
    private void browsers() {
        heading(t("Your browser, connected.", "متصفحك، متصل بـ GrabX."),
                t("You can skip this step and connect later from Settings → Browser extension.", "بتقدر تتخطى هالخطوة وتربط لاحقًا من الإعدادات ← إضافة المتصفح."));
        if (browserPane == null) browserPane = new BrowserIntegrationPane(value -> linked = value);
        browserPane.setLanguage(ar() ? "ar" : "en");
        content.getChildren().add(browserPane);
    }
    private void open(String url) {
        try { Desktop.getDesktop().browse(URI.create(url)); } catch (Exception e) { error.setText(url); }
    }
    private void review() {
        heading(t("Ready for your first link.", "جاهزين لأول رابط."), t("Review your choices, then make yourself at home.", "راجع اختياراتك، ويلا نبدأ."));
        content.getChildren().add(card(label(t("Your download folder", "مجلد التنزيلات"), "setup-card-title"), label(folder.getText(), "setup-muted"),
                label(language.getValue().name() + " · " + simultaneous.get() + t(" simultaneous downloads", " تنزيلات متزامنة"), "setup-muted"),
                label(remember.isSelected() ? t("Remember last folder", "تذكّر آخر مجلد") : t("Use default folder for each new download", "استخدام الافتراضي لكل تنزيل جديد"), "setup-muted"),
                label(linked ? t("Browser host registered", "تم تسجيل رابط المتصفح") : t("Browser connection skipped", "تم تخطّي ربط المتصفح"), "setup-muted")));
        if (checks != null && checks.stream().anyMatch(c -> !c.ready())) content.getChildren().add(label(t("Some media tools still need attention. Review System check for installation links.", "بعض أدوات الوسائط لسه بحاجة تثبيت. راجع خطوة جاهزية الجهاز للروابط."), "setup-warning"));
    }
    private void advance() {
        if (step == 2 && !validateFolderStep()) return;
        if (step == 4) {
            try {
                setup.finish(folder.getText(), language.getValue().code(), remember.isSelected(), simultaneous.get(), sounds.isSelected());
                finished.run();
            } catch (SetupPreferences.FolderValidationException e) {
                step = 2; render(); validateFolderStep();
            } catch (Exception e) { error.setText(e.getMessage()); }
            return;
        }
        step++; render(); if (step == 1 && checks == null) scan();
    }
}
