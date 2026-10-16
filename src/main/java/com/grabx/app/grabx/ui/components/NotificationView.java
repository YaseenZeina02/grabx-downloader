package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.service.NotificationCenter;
import javafx.animation.PauseTransition;
import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.*;
import javafx.util.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Non-modal banners and a shared inbox in the full and compact windows. FX thread only. */
public final class NotificationView implements AutoCloseable {
    private static final String BELL = "M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4a1.5 1.5 0 0 0-3 0v.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1z";
    private final NotificationCenter center;
    private final Supplier<Window> owner;
    private final Popup inbox = new Popup(), toast = new Popup();
    private final PauseTransition hide = new PauseTransition(Duration.seconds(8));
    private final PauseTransition coalesce = new PauseTransition(Duration.millis(350));
    private final List<Button> buttons = new ArrayList<>();
    private final VBox list = new VBox(8);
    private final ListChangeListener<NotificationCenter.Entry> entriesChanged = change -> rebuild();
    private final InvalidationListener unreadChanged = ignored -> updateButtons();
    private NotificationCenter.Entry pending;
    private Window anchored;
    private final InvalidationListener moved = ignored -> hideBanners();
    private final InvalidationListener focusChanged = ignored -> { if (anchored != null && !anchored.isFocused()) hideBanners(); };
    public NotificationView(NotificationCenter center, Supplier<Window> owner) {
        this.center = center; this.owner = owner;
        inbox.setAutoHide(true); inbox.setHideOnEscape(true); toast.setAutoHide(false);
        hide.setOnFinished(e -> hideBanners());
        coalesce.setOnFinished(e -> showBanner());
        center.onBanner(entry -> { pending = entry; coalesce.playFromStart(); });
        center.entries().addListener(entriesChanged); center.unreadProperty().addListener(unreadChanged);
    }
    public Button createButton(boolean compact) {
        Button button = new Button();
        button.setId(compact ? "compact-notifications" : "notifications-button");
        button.getStyleClass().add("notification-bell");
        if (compact) button.getStyleClass().add("notification-bell-compact");
        button.getStylesheets().add(stylesheet()); button.setTooltip(new Tooltip("Notifications"));
        button.setOnAction(e -> toggleInbox(button)); buttons.add(button); updateButtons(); return button;
    }
    private void updateButtons() {
        int count = center.unreadProperty().get();
        for (Button button : buttons) {
            StackPane graphic = new StackPane(icon(BELL));
            if (count > 0) {
                Label badge = new Label(count > 9 ? "9+" : Integer.toString(count));
                badge.getStyleClass().add("notification-badge"); StackPane.setAlignment(badge, Pos.TOP_RIGHT);
                badge.setTranslateX(8); badge.setTranslateY(-6); graphic.getChildren().add(badge);
            }
            button.setGraphic(graphic); button.setAccessibleText("Notifications, " + count + " unread");
        }
    }
    private void toggleInbox(Button anchor) {
        if (inbox.isShowing()) { inbox.hide(); return; }
        hideBanners();
        Window window = anchor.getScene().getWindow();
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) return;
        center.markRead(); rebuild();
        Label heading = label("Notifications", "notification-heading");
        Button clear = new Button("Clear all"); clear.setId("notifications-clear");
        clear.getStyleClass().add("notification-link"); clear.setOnAction(e -> center.clear());
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(12, heading, gap, clear); header.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroll = new ScrollPane(list); scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("notification-scroll");
        Rectangle2D screen = screen(window);
        scroll.setPrefViewportHeight(Math.min(390, screen.getHeight() - 160));
        VBox content = new VBox(12, header, label("Recent events · this session", "notification-caption"), scroll);
        content.getStyleClass().addAll("notification-surface", "notification-inbox");
        content.getStylesheets().add(stylesheet()); content.setPrefWidth(Math.min(410, screen.getWidth() - 24));
        inbox.getContent().setAll(content);
        inbox.show(window, Math.max(screen.getMinX() + 12, Math.min(bounds.getMaxX() - content.getPrefWidth(), screen.getMaxX() - content.getPrefWidth() - 12)),
                Math.max(screen.getMinY() + 12, Math.min(bounds.getMaxY() + 8, screen.getMaxY() - 500)));
    }
    private void rebuild() {
        list.getChildren().clear();
        if (center.entries().isEmpty()) {
            VBox empty = new VBox(10, icon(BELL), label("You're all caught up", "notification-title"),
                    label("Download and connection updates appear here.", "notification-body"));
            empty.setAlignment(Pos.CENTER); empty.setPadding(new Insets(35, 12, 35, 12)); list.getChildren().add(empty);
        } else for (NotificationCenter.Entry entry : center.entries()) list.getChildren().add(card(entry, false));
    }
    private VBox card(NotificationCenter.Entry entry, boolean banner) {
        String tone = switch (entry.kind()) {
            case COMPLETED, ONLINE -> "success";
            case FAILED -> "error";
            default -> "warning";
        };
        String symbol = switch (tone) {
            case "success" -> "M9 16.2 4.8 12 3.4 13.4 9 19 21 7 19.6 5.6z";
            case "error" -> "M11 7h2v6h-2zm0 8h2v2h-2zM12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2a8 8 0 1 1 0 16 8 8 0 0 1 0-16z";
            default -> "M1 21h22L12 2 1 21zm11-3a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm-1-9h2v5h-2z";
        };
        StackPane mark = new StackPane(icon(symbol)); mark.getStyleClass().add("notification-mark");
        Label title = label(entry.title(), "notification-title"); title.setWrapText(true); title.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(title, Priority.ALWAYS);
        HBox header = new HBox(10, mark, title); header.setAlignment(Pos.CENTER_LEFT);
        if (banner) {
            Button dismiss = new Button("×"); dismiss.setAccessibleText("Dismiss notification");
            dismiss.getStyleClass().add("notification-dismiss"); dismiss.setOnAction(e -> hideBanners()); header.getChildren().add(dismiss);
        }
        Label message = label(entry.message(), "notification-body"); message.setWrapText(true); message.setMaxWidth(Double.MAX_VALUE);
        message.setMaxHeight(90); message.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox box = new VBox(9, header, message);
        box.getStyleClass().addAll("notification-card", "notification-" + tone);
        if (!banner) box.getChildren().add(label(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(entry.time()), "notification-caption"));
        else box.getChildren().add(label("GrabX · View all from the bell", "notification-caption"));
        return box;
    }
    private void showBanner() {
        NotificationCenter.Entry entry = pending; pending = null;
        Window window = owner.get();
        if (entry == null || window == null || !window.isShowing() || !window.isFocused() || inbox.isShowing()
                || !center.preferences().enabled() || !center.preferences().banners()) return;
        detachAnchor(); anchored = window;
        window.xProperty().addListener(moved); window.yProperty().addListener(moved);
        window.widthProperty().addListener(moved); window.heightProperty().addListener(moved);
        window.showingProperty().addListener(moved); window.focusedProperty().addListener(focusChanged);
        VBox content = card(entry, true); content.getStyleClass().add("notification-surface");
        content.getStylesheets().add(stylesheet());
        content.setPrefWidth(Math.min(370, Math.max(260, window.getWidth() - 48)));
        toast.getContent().setAll(content);
        Rectangle2D screen = screen(window);
        double x = Math.max(screen.getMinX() + 12, Math.min(window.getX() + window.getWidth() - content.getPrefWidth() - 16, screen.getMaxX() - content.getPrefWidth() - 12));
        double y = Math.max(screen.getMinY() + 12, Math.min(window.getY() + window.getHeight() - 210, screen.getMaxY() - 220));
        toast.show(window, x, y);
        content.setOnMouseEntered(e -> hide.pause()); content.setOnMouseExited(e -> hide.playFromStart());
        hide.playFromStart();
    }
    public void hideBanners() { hide.stop(); coalesce.stop(); pending = null; toast.hide(); detachAnchor(); }
    public void hidePopups() { hideBanners(); inbox.hide(); }
    private void detachAnchor() {
        if (anchored == null) return;
        anchored.xProperty().removeListener(moved); anchored.yProperty().removeListener(moved);
        anchored.widthProperty().removeListener(moved); anchored.heightProperty().removeListener(moved);
        anchored.showingProperty().removeListener(moved); anchored.focusedProperty().removeListener(focusChanged); anchored = null;
    }
    private static Rectangle2D screen(Window owner) {
        var screens = Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
        return (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
    }
    private static SVGPath icon(String path) { SVGPath icon = new SVGPath(); icon.setContent(path); icon.getStyleClass().add("notification-icon"); return icon; }
    private static Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }
    private static String stylesheet() { return NotificationView.class.getResource("/com/grabx/app/grabx/styles/notifications.css").toExternalForm(); }
    @Override public void close() {
        hidePopups(); center.onBanner(entry -> {}); center.entries().removeListener(entriesChanged);
        center.unreadProperty().removeListener(unreadChanged); buttons.clear();
    }
}
