package com.smplkit.logging.adapters;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Contract for pluggable logging framework integration.
 *
 * <p>Adapters bridge the smplkit logging runtime to a specific logging framework
 * (e.g., JUL, Logback, Log4j2).</p>
 */
public interface LoggingAdapter {
    /** Human-readable adapter name for diagnostics (e.g., "jul"). */
    String name();

    /**
     * Scan the runtime for existing loggers.
     * @return list of discovered loggers with names and smplkit level strings
     */
    List<DiscoveredLogger> discover();

    /**
     * Set the level on a specific logger.
     * @param loggerName the original (non-normalized) logger name
     * @param level smplkit level string (e.g., "DEBUG", "INFO", "WARN")
     */
    void applyLevel(String loggerName, String level);

    /**
     * Install continuous discovery hook.
     * The callback receives (original_name, smplkit_level_string) whenever
     * a new logger is created in the framework.
     * May be a no-op if the framework doesn't support creation interception.
     */
    void installHook(BiConsumer<String, String> onNewLogger);

    /** Remove the hook installed by installHook. Called on close(). */
    void uninstallHook();
}
