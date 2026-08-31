package com.grabx.app.grabx.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Central logging setup for GrabX. */
public final class AppLog {
    private static final String LOGGER_NAMESPACE = "com.grabx.app.grabx";
    private static final int MAX_LOG_BYTES = 2 * 1024 * 1024;
    private static final int LOG_FILE_COUNT = 3;
    private static boolean configured;

    private AppLog() {}

    public static Logger get(Class<?> owner) {
        configureOnce();
        return Logger.getLogger(owner == null ? LOGGER_NAMESPACE : owner.getName());
    }

    public static Path logDirectory() {
        return Paths.get(System.getProperty("user.home"), ".grabx", "logs");
    }

    private static synchronized void configureOnce() {
        if (configured) return;
        configured = true;

        try {
            Path directory = logDirectory();
            Files.createDirectories(directory);

            String pattern = directory.resolve("grabx-%g.log").toString();
            FileHandler fileHandler = new FileHandler(
                    pattern,
                    MAX_LOG_BYTES,
                    LOG_FILE_COUNT,
                    true
            );
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new GrabXFormatter());

            Logger appLogger = Logger.getLogger(LOGGER_NAMESPACE);
            appLogger.setLevel(Level.ALL);
            appLogger.addHandler(fileHandler);
        } catch (IOException | SecurityException error) {
            Logger.getLogger(LOGGER_NAMESPACE).log(
                    Level.WARNING,
                    "Could not initialize GrabX file logging",
                    error
            );
        }
    }

    private static final class GrabXFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String source = record.getLoggerName();
            int separator = source == null ? -1 : source.lastIndexOf('.');
            if (separator >= 0) source = source.substring(separator + 1);

            StringBuilder line = new StringBuilder(192)
                    .append(Instant.ofEpochMilli(record.getMillis()))
                    .append(" [").append(record.getLevel().getName()).append("] ")
                    .append(source == null ? "GrabX" : source)
                    .append(" - ").append(formatMessage(record))
                    .append(System.lineSeparator());

            Throwable thrown = record.getThrown();
            if (thrown != null) {
                line.append(thrown).append(System.lineSeparator());
                for (StackTraceElement element : thrown.getStackTrace()) {
                    line.append("    at ").append(element).append(System.lineSeparator());
                }
            }
            return line.toString();
        }
    }
}
