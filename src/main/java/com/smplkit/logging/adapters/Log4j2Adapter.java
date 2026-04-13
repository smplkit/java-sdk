package com.smplkit.logging.adapters;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;

/**
 * Logging adapter for Apache Log4j2.
 *
 * <p>This adapter is available only when Log4j2 Core is on the classpath.</p>
 */
public final class Log4j2Adapter implements LoggingAdapter {

    private volatile BiConsumer<String, String> hook;
    private final Set<String> knownNames = new CopyOnWriteArraySet<>();

    @Override
    public String name() {
        return "log4j2";
    }

    @Override
    public List<DiscoveredLogger> discover() {
        List<DiscoveredLogger> result = new ArrayList<>();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Collection<org.apache.logging.log4j.core.Logger> loggers = context.getLoggers();
        for (org.apache.logging.log4j.core.Logger logger : loggers) {
            String loggerName = logger.getName();
            knownNames.add(loggerName);
            String level = log4j2ToSmplLevel(logger.getLevel());
            String resolvedLevel = resolveLog4j2Level(logger, context);
            result.add(new DiscoveredLogger(loggerName, level, resolvedLevel));
        }
        return result;
    }

    @Override
    public void applyLevel(String loggerName, String level) {
        Level log4jLevel = smplToLog4j2Level(level);
        Configurator.setLevel(loggerName, log4jLevel);
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
     * Checks for newly created loggers and notifies the registered hook.
     */
    public void pollForNewLoggers() {
        BiConsumer<String, String> callback = this.hook;
        if (callback == null) return;

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        for (org.apache.logging.log4j.core.Logger logger : context.getLoggers()) {
            String loggerName = logger.getName();
            if (knownNames.add(loggerName)) {
                String resolvedLevel = resolveLog4j2Level(logger, context);
                callback.accept(loggerName, resolvedLevel);
            }
        }
    }

    /** Converts a smplkit level string to a Log4j2 Level. */
    static Level smplToLog4j2Level(String level) {
        return switch (level) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARN" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            case "FATAL" -> Level.FATAL;
            case "SILENT" -> Level.OFF;
            default -> Level.DEBUG;
        };
    }

    /**
     * Converts a Log4j2 Level to a smplkit level string.
     *
     * <p>Returns {@code null} when {@code level} is {@code null}, which means the level is
     * inherited and was not explicitly set on this logger.</p>
     */
    static String log4j2ToSmplLevel(Level level) {
        if (level == null) return null;
        if (level.equals(Level.OFF)) return "SILENT";
        if (level.equals(Level.FATAL)) return "FATAL";
        if (level.isMoreSpecificThan(Level.ERROR)) return "ERROR";
        if (level.isMoreSpecificThan(Level.WARN)) return "WARN";
        if (level.isMoreSpecificThan(Level.INFO)) return "INFO";
        if (level.isMoreSpecificThan(Level.DEBUG)) return "DEBUG";
        return "TRACE";
    }

    /**
     * Walks the Log4j2 logger hierarchy to determine the effective (resolved) level.
     *
     * <p>Mirrors Python's {@code logging.Logger.getEffectiveLevel()}: ascends the parent chain
     * until it finds a logger with an explicitly-configured level.  Falls back to {@code "INFO"}
     * if no ancestor has a level set.</p>
     */
    static String resolveLog4j2Level(org.apache.logging.log4j.core.Logger logger,
                                     LoggerContext context) {
        org.apache.logging.log4j.core.Logger current = logger;
        while (current != null) {
            String mapped = log4j2ToSmplLevel(current.getLevel());
            if (mapped != null) return mapped;
            // Walk up: Log4j2 core Logger exposes getParent()
            org.apache.logging.log4j.core.Logger parent = current.getParent();
            if (parent == null || parent == current) break;
            current = parent;
        }
        return "INFO";
    }
}
