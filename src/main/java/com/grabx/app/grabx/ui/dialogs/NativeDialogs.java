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
     * - Windows: PowerShell MessageBox (native)
     * - Linux: zenity (if available)
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
                String msg = "Are you sure you want to remove\n\"" + safeName + "\"?";

                String script;
                if (canDeleteFiles) {
                    // 3 buttons: Cancel / Remove / Remove & Delete
                    script =
                            "set theTitle to \"" + escapeAppleScript(title) + "\"\n" +
                                    "set theMsg to \"" + escapeAppleScript(msg) + "\"\n" +
                                    "set r to display dialog theMsg with title theTitle " +
                                    "buttons {\"Cancel\", \"Remove\", \"Remove & Delete\"} " +
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

                if ("Remove & Delete".equalsIgnoreCase(out)) return RemoveChoice.REMOVE_AND_DELETE;
                if ("Remove".equalsIgnoreCase(out)) return RemoveChoice.REMOVE_ONLY;
                return RemoveChoice.CANCEL;

            } catch (Exception ignored) {
            }
        }

        // -------- Windows (PowerShell MessageBox) --------
        if (os.contains("win")) {
            try {
                String title = "Remove download";
                String msg = "Remove this download?\n\n\"" + safeName + "\"\n\n" +
                        (canDeleteFiles
                                ? "Yes: Remove & delete files\nNo: Remove only\nCancel: Keep"
                                : "Yes: Remove\nNo: Keep");

                String ps;
                if (canDeleteFiles) {
                    // Yes = delete, No = remove only, Cancel = cancel
                    ps = "Add-Type -AssemblyName PresentationFramework; " +
                            "$r=[System.Windows.MessageBox]::Show('" + msg.replace("'", "''") + "','" +
                            title.replace("'", "''") + "','YesNoCancel','Warning'); " +
                            "Write-Output $r";
                } else {
                    ps = "Add-Type -AssemblyName PresentationFramework; " +
                            "$r=[System.Windows.MessageBox]::Show('" + msg.replace("'", "''") + "','" +
                            title.replace("'", "''") + "','YesNo','Warning'); " +
                            "Write-Output $r";
                }

                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", ps)
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

                if (canDeleteFiles) {
                    if (out.equalsIgnoreCase("Yes")) return RemoveChoice.REMOVE_AND_DELETE;
                    if (out.equalsIgnoreCase("No")) return RemoveChoice.REMOVE_ONLY;
                    return RemoveChoice.CANCEL;
                } else {
                    if (out.equalsIgnoreCase("Yes")) return RemoveChoice.REMOVE_ONLY;
                    return RemoveChoice.CANCEL;
                }

            } catch (Exception ignored) {
            }
        }

        // -------- Linux (zenity) --------
        try {
            String title = "Remove download";
            String msg = "Remove this download?\n\n\"" + safeName + "\"";

            if (canDeleteFiles) {
                Process p = new ProcessBuilder(
                        "sh", "-lc",
                        "command -v zenity >/dev/null 2>&1 && " +
                                "zenity --question --title='" + title.replace("'", "'\\''") + "' " +
                                "--text='" + msg.replace("'", "'\\''") + "\\n\\nOK=Remove & Delete, Extra=Remove only' " +
                                "--ok-label='Remove & Delete' --cancel-label='Cancel' --extra-button='Remove'"
                ).redirectErrorStream(true).start();

                int code = p.waitFor();
                if (code == 0) return RemoveChoice.REMOVE_AND_DELETE; // OK
                if (code == 5) return RemoveChoice.REMOVE_ONLY;       // extra button
                return RemoveChoice.CANCEL;

            } else {
                Process p = new ProcessBuilder(
                        "sh", "-lc",
                        "command -v zenity >/dev/null 2>&1 && " +
                                "zenity --question --title='" + title.replace("'", "'\\''") + "' " +
                                "--text='" + msg.replace("'", "'\\''") + "' " +
                                "--ok-label='Remove' --cancel-label='Cancel'"
                ).redirectErrorStream(true).start();

                int code = p.waitFor();
                if (code == 0) return RemoveChoice.REMOVE_ONLY;
                return RemoveChoice.CANCEL;
            }

        } catch (Exception ignored) {
        }

        // Fallback: cancel
        return RemoveChoice.CANCEL;
    }
}