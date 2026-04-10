package com.smplkit.logging.adapters;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;

/**
 * Logging adapter for SLF4J + Logback.
 *
 * <p>This adapter is available only when Logback Classic is on the classpath.</p>
 */
public final class Slf4jLogbackAdapter implements LoggingAdapter {

    private volatile TurboFilter turboFilter;
    private final Set<String> knownNames = new CopyOnWriteArraySet<>();

    @Override
    public String name() {
        return "slf4j-logback";
    }

    @Override
    public List<DiscoveredLogger> discover() {
        List<DiscoveredLogger> result = new ArrayList<>();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (Logger logger : context.getLoggerList()) {
            String loggerName = logger.getName();
            knownNames.add(loggerName);
            String level = logbackToSmplLevel(logger.getEffectiveLevel());
            result.add(new DiscoveredLogger(loggerName, level));
        }
        return result;
    }

    @Override
    public void applyLevel(String loggerName, String level) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(loggerName);
        logger.setLevel(smplToLogbackLevel(level));
    }

    @Override
    public void installHook(BiConsumer<String, String> onNewLogger) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        TurboFilter filter = new TurboFilter() {
            @Override
            public FilterReply decide(Marker marker, Logger logger, Level level,
                                      String format, Object[] params, Throwable t) {
                if (logger != null && knownNames.add(logger.getName())) {
                    String smplLevel = logbackToSmplLevel(logger.getEffectiveLevel());
                    onNewLogger.accept(logger.getName(), smplLevel);
                }
                return FilterReply.NEUTRAL;
            }
        };
        filter.setName("smplkit-discovery");
        context.addTurboFilter(filter);
        this.turboFilter = filter;
    }

    @Override
    public void uninstallHook() {
        TurboFilter filter = this.turboFilter;
        if (filter != null) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.getTurboFilterList().remove(filter);
            this.turboFilter = null;
        }
    }

    /** Map a smplkit level string to a Logback Level. */
    static Level smplToLogbackLevel(String level) {
        return switch (level) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARN" -> Level.WARN;
            case "ERROR", "FATAL" -> Level.ERROR;
            case "SILENT" -> Level.OFF;
            default -> Level.DEBUG;
        };
    }

    /** Map a Logback Level to a smplkit level string. */
    static String logbackToSmplLevel(Level level) {
        if (level == null) return "DEBUG";
        if (level.equals(Level.OFF)) return "SILENT";
        if (level.isGreaterOrEqual(Level.ERROR)) return "ERROR";
        if (level.isGreaterOrEqual(Level.WARN)) return "WARN";
        if (level.isGreaterOrEqual(Level.INFO)) return "INFO";
        if (level.isGreaterOrEqual(Level.DEBUG)) return "DEBUG";
        return "TRACE";
    }
}
