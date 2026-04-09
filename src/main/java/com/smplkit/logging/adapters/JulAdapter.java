package com.smplkit.logging.adapters;

import com.smplkit.LogLevel;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * Logging adapter for java.util.logging (JUL).
 *
 * <p>JUL is always available on the JVM, so this adapter never fails to load.</p>
 */
public final class JulAdapter implements LoggingAdapter {

    private volatile BiConsumer<String, String> hook;
    private final Set<String> knownNames = new CopyOnWriteArraySet<>();

    @Override
    public String name() {
        return "jul";
    }

    @Override
    public List<DiscoveredLogger> discover() {
        List<DiscoveredLogger> result = new ArrayList<>();
        LogManager manager = LogManager.getLogManager();
        Enumeration<String> names = manager.getLoggerNames();
        while (names.hasMoreElements()) {
            String loggerName = names.nextElement();
            knownNames.add(loggerName);
            java.util.logging.Logger julLogger = manager.getLogger(loggerName);
            String level = julToSmplLevel(julLogger != null ? julLogger.getLevel() : null);
            result.add(new DiscoveredLogger(loggerName, level));
        }
        return result;
    }

    @Override
    public void applyLevel(String loggerName, String level) {
        Level julLevel = smplStringToJulLevel(level);
        java.util.logging.Logger.getLogger(loggerName).setLevel(julLevel);
    }

    @Override
    public void installHook(BiConsumer<String, String> onNewLogger) {
        this.hook = onNewLogger;
    }

    @Override
    public void uninstallHook() {
        this.hook = null;
    }

    /**
     * Poll for new loggers. Called by the client on each refresh cycle.
     * Fires the hook callback for any loggers not seen during discover() or previous polls.
     */
    public void pollForNewLoggers() {
        BiConsumer<String, String> callback = this.hook;
        if (callback == null) return;

        LogManager manager = LogManager.getLogManager();
        Enumeration<String> names = manager.getLoggerNames();
        while (names.hasMoreElements()) {
            String loggerName = names.nextElement();
            if (knownNames.add(loggerName)) {
                java.util.logging.Logger julLogger = manager.getLogger(loggerName);
                String level = julToSmplLevel(julLogger != null ? julLogger.getLevel() : null);
                callback.accept(loggerName, level);
            }
        }
    }

    /** Map a smplkit LogLevel enum to a java.util.logging.Level. */
    public static Level smplToJulLevel(LogLevel level) {
        return switch (level) {
            case TRACE -> Level.FINEST;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR, FATAL -> Level.SEVERE;
            case SILENT -> Level.OFF;
        };
    }

    /** Map a smplkit level string to a java.util.logging.Level. */
    public static Level smplStringToJulLevel(String level) {
        return smplToJulLevel(LogLevel.valueOf(level));
    }

    /** Map a JUL Level to a smplkit level string. Returns "DEBUG" for null (inherit). */
    static String julToSmplLevel(Level julLevel) {
        if (julLevel == null) return "DEBUG";
        int value = julLevel.intValue();
        if (value == Level.OFF.intValue()) return "SILENT";
        if (value >= Level.SEVERE.intValue()) return "ERROR";
        if (value >= Level.WARNING.intValue()) return "WARN";
        if (value >= Level.INFO.intValue()) return "INFO";
        if (value >= Level.FINE.intValue()) return "DEBUG";
        return "TRACE"; // FINEST, FINER, or below
    }
}
