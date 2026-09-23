package com.grabx.app.grabx.ui.dialogs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class NativeDialogs {

    private NativeDialogs() {}

    public enum RemoveChoice {
        CANCEL,
        REMOVE_ONLY,
        REMOVE_AND_DELETE
    }

    private static String escapeAppleScript(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Native confirm per OS.
     * - macOS: osascript display dialog (native)
     * - Windows/Linux: JavaFX confirmation with explicit actions
     *
     * @param fileName        display name for the file/task
     * @param canDeleteFiles  if true, show option to delete files too
     */
    public static RemoveChoice showRemoveConfirm(String fileName, boolean canDeleteFiles) {
        String safeName = (fileName == null || fileName.isBlank()) ? "this download" : fileName.trim();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        // -------- macOS (native) --------
        if (os.contains("mac")) {
            try {
                String title = "Remove download";
                String msg = "What would you like to do with this download?\n\n\"" + safeName + "\"";

                String script;
                if (canDeleteFiles) {
                    // 3 buttons: Cancel / Remove / Delete from Device
                    script =
                            "set theTitle to \"" + escapeAppleScript(title) + "\"\n" +
                                    "set theMsg to \"" + escapeAppleScript(msg) + "\"\n" +
                                    "set r to display dialog theMsg with title theTitle " +
                                    "buttons {\"Cancel\", \"Remove\", \"Delete from Device\"} " +
                                    "default button \"Remove\" cancel button \"Cancel\" with icon caution\n" +
                                    "button returned of r";
                } else {
                    script =
                            "set theTitle to \"" + escapeAppleScript(title) + "\"\n" +
                                    "set theMsg to \"" + escapeAppleScript(msg) + "\"\n" +
                                    "set r to display dialog theMsg with title theTitle " +
                                    "buttons {\"Cancel\", \"Remove\"} " +
                                    "default button \"Remove\" cancel button \"Cancel\" with icon caution\n" +
                                    "button returned of r";
                }

                Process p = new ProcessBuilder("osascript", "-e", script)
                        .redirectErrorStream(true)
                        .start();

                String out;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    out = br.readLine();
                }
                p.waitFor();

                if (out == null) return RemoveChoice.CANCEL;
                out = out.trim();

                if ("Delete from Device".equalsIgnoreCase(out)) return RemoveChoice.REMOVE_AND_DELETE;
                if ("Remove".equalsIgnoreCase(out)) return RemoveChoice.REMOVE_ONLY;
                return RemoveChoice.CANCEL;

            } catch (Exception ignored) {
            }
        }

        // JavaFX provides the same explicit choices on Windows and Linux without
        // depending on PowerShell/WPF, zenity, a shell, or platform-specific exit codes.
        return showPortableRemoveConfirm(safeName, canDeleteFiles);
    }

    private static RemoveChoice showPortableRemoveConfirm(String name, boolean canDeleteFiles) {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            var task = new java.util.concurrent.FutureTask<RemoveChoice>(() -> showPortableRemoveConfirm(name, canDeleteFiles));
            javafx.application.Platform.runLater(task);
            try { return task.get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return RemoveChoice.CANCEL; }
            catch (Exception e) { return RemoveChoice.CANCEL; }
        }
        var remove = new javafx.scene.control.ButtonType("Remove from list");
        var delete = new javafx.scene.control.ButtonType("Delete from device");
        var cancel = javafx.scene.control.ButtonType.CANCEL;
        var dialog = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isFocused).findFirst().ifPresent(dialog::initOwner);
        dialog.setTitle("Remove download");
        dialog.setHeaderText("Remove this download?");
        dialog.setContentText(name);
        dialog.getButtonTypes().setAll(canDeleteFiles ? java.util.List.of(remove, delete, cancel) : java.util.List.of(remove, cancel));
        var choice = dialog.showAndWait().orElse(cancel);
        return choice == delete ? RemoveChoice.REMOVE_AND_DELETE : choice == remove ? RemoveChoice.REMOVE_ONLY : RemoveChoice.CANCEL;
    }
}
