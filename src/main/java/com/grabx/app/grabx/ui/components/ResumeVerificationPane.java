package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** A distinct pre-resume phase; its network metrics are not download progress. */
final class ResumeVerificationPane extends VBox {
    private final Label heading = new Label();
    private final Label speed = new Label();
    private final Label remaining = new Label();
    private final Label saved = new Label();
    private BooleanBinding active;

    ResumeVerificationPane() {
        this(false);
    }

    ResumeVerificationPane(boolean compact) {
        super(compact ? 4 : 7);
        setId("resume-verification");
        setMinWidth(0);
        setPadding(compact ? new Insets(6, 8, 6, 8) : new Insets(10, 12, 10, 12));
        setStyle("-fx-background-color: rgba(245,183,78,0.07); -fx-background-radius: 10;"
                + "-fx-border-color: rgba(245,183,78,0.3); -fx-border-radius: 10;");
        heading.setStyle("-fx-text-fill: #f5c775; -fx-font-weight: bold; -fx-font-size: " + (compact ? 11 : 13) + "px;");
        heading.setWrapText(true);
        FlowPane metrics = new FlowPane(compact ? 10 : 20, compact ? 3 : 7, speed, remaining, saved);
        for (Label label : new Label[]{speed, remaining, saved}) {
            label.setStyle("-fx-text-fill: #e1e6ed; -fx-font-size: " + (compact ? 10 : 12) + "px;");
        }
        Label explanation = new Label(compact ? "Download resumes after checking."
                : "Reading saved data from the server to check it. Download resumes after this step.");
        explanation.setWrapText(true);
        explanation.setStyle("-fx-text-fill: #adb7c5; -fx-font-size: " + (compact ? 10 : 12) + "px;");
        getChildren().addAll(heading, metrics, explanation);
        setVisible(false);
        setManaged(false);
    }

    void bind(DownloadRow row) {
        unbind();
        active = Bindings.createBooleanBinding(() -> row.getState() == DownloadRow.State.DOWNLOADING
                && row.status.get() != null && row.status.get().startsWith("Verifying saved data"), row.state, row.status);
        visibleProperty().bind(active);
        managedProperty().bind(active);
        heading.textProperty().bind(row.status.map(value -> value == null ? "Checking before resume"
                : value.replace("Verifying saved data", "Checking before resume")
                       .replace(" • re-reading from server", "")));
        speed.textProperty().bind(row.speed.map(value -> "Verification speed: " + available(value)));
        remaining.textProperty().bind(row.eta.map(value -> "Check time left: "
                + (value == null || value.isBlank() ? "Estimating…" : "~" + DownloadEtaFormatter.format(value))));
        saved.textProperty().bind(row.size.map(value -> "Saved: " + (value == null ? "" : value)));
    }

    void unbind() {
        visibleProperty().unbind(); managedProperty().unbind();
        heading.textProperty().unbind(); speed.textProperty().unbind();
        remaining.textProperty().unbind(); saved.textProperty().unbind();
        if (active != null) { active.dispose(); active = null; }
        setVisible(false); setManaged(false);
    }

    private static String available(String value) {
        return value == null || value.isBlank() ? "Measuring…" : value;
    }
}
