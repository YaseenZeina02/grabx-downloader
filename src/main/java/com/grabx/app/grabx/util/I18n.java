package com.grabx.app.grabx.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grabx.app.grabx.core.service.LanguagePreferences;
import javafx.beans.property.Property;
import javafx.collections.ListChangeListener;
import javafx.geometry.NodeOrientation;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.text.Text;
import javafx.stage.*;
import java.util.*;

/** Presentation-only translation. Download modes, statuses and user data retain their original values. */
public final class I18n {
    private static boolean installed;
    private static String language = "en";
    private static final javafx.beans.property.StringProperty LOCALE = new javafx.beans.property.SimpleStringProperty("en");
    private static final Map<Property<String>, Source> SOURCES = new WeakHashMap<>();
    private static final Map<Node, NodeOrientation> ROOT_ORIENTATIONS = new WeakHashMap<>();
    private static final Map<Node, String> ROOT_STYLES = new WeakHashMap<>();
    private static final Set<Node> NODES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final class Source { String value; boolean writing; Source(String value) { this.value = value; } }
    public static javafx.beans.binding.StringBinding binding(javafx.beans.value.ObservableValue<String> source) {
        return javafx.beans.binding.Bindings.createStringBinding(() -> text(source.getValue()), source, LOCALE);
    }
    private static final Map<String, Map<String, String>> CATALOG = load();
    private static final String WATCHED = I18n.class.getName();
    private I18n() { }
    private static Map<String, Map<String, String>> load() {
        try (var input = I18n.class.getResourceAsStream("/com/grabx/app/grabx/i18n/messages.json")) {
            return new ObjectMapper().readValue(input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    public static String text(String source) { return text(source, LOCALE.get()); }
    public static String text(String source, String code) {
        if (source == null || code.equals("en")) return source;
        var entries = CATALOG.getOrDefault(code, Map.of());
        String exact = entries.get(source);
        if (exact != null) return exact;
        if (source.contains(" • ")) return Arrays.stream(source.split(" • ", -1)).map(item -> text(item, code)).collect(java.util.stream.Collectors.joining(" • "));
        for (String prefix : List.of("Download size: ", "Verification speed: ", "Check time left: ", "Saved: ", "Resume support: "))
            if (source.startsWith(prefix)) return text(prefix.stripTrailing(), code) + " " + text(source.substring(prefix.length()), code);
        if (source.matches("\\d+ active")) return source.replace("active", entries.getOrDefault("active", "active"));
        if (source.matches("Up to \\d+ connections")) return entries.getOrDefault("Up to {0} connections", "Up to {0} connections").replace("{0}", source.replaceAll("\\D", ""));
        if (source.endsWith(" (source streams)")) return text(source.substring(0, source.length() - 17), code) + " " + text("(source streams)", code);
        if (source.startsWith("Notifications, ") && source.endsWith(" unread"))
            return text("Notifications", code) + ", " + source.substring(15, source.length() - 7) + " " + text("unread", code);
        if (source.matches("[0-9 ·]*(?:downloading|preparing|queued|completed)[0-9 a-z·]*")) {
            String translated = source;
            for (String word : List.of("downloading", "preparing", "queued", "completed")) translated = translated.replace(word, text(word, code));
            return translated;
        }
        return source;
    }
    public static void install(String code) {
        language = LanguagePreferences.valid(code) ? code : "en";
        if (!installed) Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) if (change.wasAdded()) for (Window window : change.getAddedSubList()) localize(window);
        });
        installed = true;
        if (language.equals("ar")) {
            javafx.scene.text.Font.loadFont(I18n.class.getResource("/fonts/Cairo-Regular.ttf").toExternalForm(), 14);
            javafx.scene.text.Font.loadFont(I18n.class.getResource("/fonts/Cairo-Bold.ttf").toExternalForm(), 14);
        }
        LOCALE.set(language);
        for (var entry : new ArrayList<>(SOURCES.entrySet())) refresh(entry.getKey(), entry.getValue());
        for (Node node : new ArrayList<>(NODES)) direction(node);
        for (Window window : Window.getWindows()) localize(window);
    }
    public static void localize(Window window) {
        if (window.getScene() != null) localizeScene(window.getScene());
        if (window.getProperties().putIfAbsent(WATCHED, true) != null) return;
        window.sceneProperty().addListener((obs, old, scene) -> { if (scene != null) localizeScene(scene); });
        if (window instanceof ContextMenu menu) menu.getItems().forEach(I18n::menuItem);
        if (window instanceof Tooltip tooltip) watch(tooltip.textProperty());
        if (window instanceof Stage stage) watch(stage.titleProperty());
    }
    private static void localizeScene(Scene scene) {
        localize(scene.getRoot());
        if (scene.getProperties().putIfAbsent(WATCHED, true) == null)
            scene.rootProperty().addListener((obs, old, root) -> localize(root));
    }
    public static void localize(Node node) {
        if (node.getProperties().putIfAbsent(WATCHED, true) != null) { direction(node); return; }
        NODES.add(node);
        direction(node);
        watch(node.accessibleTextProperty());
        if (node instanceof TextInputControl input) {
            input.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
            watch(input.promptTextProperty()); // Never translate user-entered text.
        }
        if (node instanceof Labeled labeled) {
            labeled.textProperty().addListener((obs, old, value) -> direction(labeled));
            if (!Boolean.TRUE.equals(node.getProperties().get("grabx.userText"))) watch(labeled.textProperty());
            if (labeled.getTooltip() != null) watch(labeled.getTooltip().textProperty());
            labeled.tooltipProperty().addListener((obs, old, tip) -> { if (tip != null) watch(tip.textProperty()); });
        }
        // Text inside control skins is bound; the owning control is translated instead.
        if (node instanceof Text text) watch(text.textProperty());
        if (node instanceof MenuButton menu) {
            menu.getItems().forEach(I18n::menuItem);
            menu.getItems().addListener((ListChangeListener<MenuItem>) change -> {
                while (change.next()) if (change.wasAdded()) change.getAddedSubList().forEach(I18n::menuItem);
            });
        }
        if (node instanceof ComboBox<?> combo && combo.getConverter() == null) localizeChoices(combo);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) localize(child);
            parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) if (change.wasAdded()) for (Node child : change.getAddedSubList()) localize(child);
            });
        }
    }
    private static void menuItem(MenuItem item) {
        watch(item.textProperty());
        if (item instanceof Menu menu) menu.getItems().forEach(I18n::menuItem);
    }
    private static <T> void localizeChoices(ComboBox<T> combo) {
        if (combo.getCellFactory() != null) return;
        combo.setCellFactory(list -> choiceCell()); combo.setButtonCell(choiceCell());
    }
    private static <T> ListCell<T> choiceCell() {
        return new ListCell<>() { @Override protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty); setText(empty || item == null ? null : item.toString());
        }};
    }
    private static void direction(Node node) {
        if (node.getParent() == null) {
            ROOT_STYLES.putIfAbsent(node, node.getStyle());
            ROOT_ORIENTATIONS.putIfAbsent(node, node.getNodeOrientation());
            node.setNodeOrientation(language.equals("ar") ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
            node.setStyle(ROOT_STYLES.get(node) + (language.equals("ar") ? ";-fx-font-family: 'Cairo';" : ""));
        }
        if (node.getParent() != null && ROOT_STYLES.containsKey(node)) {
            node.setStyle(ROOT_STYLES.get(node));
            node.setNodeOrientation(ROOT_ORIENTATIONS.getOrDefault(node, NodeOrientation.INHERIT));
        }
        if (node instanceof TextInputControl) node.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        if (node instanceof Labeled labeled && labeled.getText() != null
                && labeled.getText().matches("[\\s↓≈~0-9].*(?:KB|MB|GB|/s|[0-9]:[0-9]).*"))
            node.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
    }
    private static String canonical(String value) {
        if (value == null || CATALOG.values().stream().anyMatch(entries -> entries.containsKey(value))) return value;
        for (var entries : CATALOG.values())
            for (var entry : entries.entrySet()) if (entry.getValue().equals(value)) return entry.getKey();
        return value;
    }
    private static void refresh(Property<String> property, Source source) {
        if (property == null || property.isBound() || source.writing) return;
        String translated = text(source.value);
        if (!Objects.equals(property.getValue(), translated)) {
            source.writing = true;
            try { property.setValue(translated); } finally { source.writing = false; }
        }
    }
    private static void watch(Property<String> property) {
        if (property.isBound() || SOURCES.containsKey(property)) return;
        Source source = new Source(canonical(property.getValue()));
        SOURCES.put(property, source);
        property.addListener((obs, old, value) -> {
            if (source.writing || property.isBound()) return;
            source.value = canonical(value); refresh(property, source);
        });
        refresh(property, source);
    }
}
