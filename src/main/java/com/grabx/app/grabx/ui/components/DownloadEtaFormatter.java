package com.grabx.app.grabx.ui.components;

/** Display both direct-download mm:ss and extractor hh:mm:ss estimates consistently. */
final class DownloadEtaFormatter {
    private DownloadEtaFormatter() {}

    static String format(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        if (!text.matches("\\d+:\\d{2}(?::\\d{2})?")) return text;
        try {
            String[] parts = text.split(":");
            long minutes = Long.parseLong(parts[0]);
            if (parts.length == 3) {
                minutes = Math.addExact(Math.multiplyExact(minutes, 60), Long.parseLong(parts[1]));
            }
            return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : text;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return text;
        }
    }
}
