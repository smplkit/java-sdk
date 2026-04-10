package com.smplkit.logging.adapters;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Contract for logging framework integration.
 *
 * <p>Implementations connect the smplkit logging service to a specific
 * logging framework (e.g., JUL, Logback, Log4j2).</p>
 */
public interface LoggingAdapter {
    /** Human-readable adapter name for diagnostics (e.g., "jul"). */
    String name();

    /**
     * Returns all loggers currently known to the framework.
     * @return list of loggers with names and level strings
     */
    List<DiscoveredLogger> discover();

    /**
     * Sets the level on a specific logger.
     * @param loggerName the logger name
     * @param level smplkit level string (e.g., "DEBUG", "INFO", "WARN")
     */
    void applyLevel(String loggerName, String level);

    /**
     * Installs a hook that is called whenever a new logger is created in the framework.
     * May be a no-op if the framework does not support this.
     */
    void installHook(BiConsumer<String, String> onNewLogger);

    /** Removes the hook installed by {@link #installHook}. */
    void uninstallHook();
}
