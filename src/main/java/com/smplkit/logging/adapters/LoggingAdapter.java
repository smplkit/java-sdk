package com.smplkit.logging.adapters;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Contract for pluggable logging framework integration.
 *
 * <p>Adapters bridge the smplkit logging runtime to a specific logging
 * framework (e.g., JUL, Logback, Log4j2). Implement this interface to add
 * support for a new logging framework.</p>
 */
public interface LoggingAdapter {

    /**
     * Human-readable adapter name for diagnostics (e.g., {@code "jul"}).
     *
     * @return the adapter name
     */
    String name();

    /**
     * Scan the runtime for existing loggers.
     *
     * <p>Returns a list of {@link DiscoveredLogger} carrying the logger name,
     * the explicit level (or {@code null}), and the effective (resolved) level.</p>
     *
     * <ul>
     *   <li>{@code level}: the raw level set on the logger, or {@code null} if the
     *     logger has no explicit level (inherits from parent / framework default).</li>
     *   <li>{@code resolvedLevel}: the resolved level the framework uses for this
     *     logger, accounting for inheritance. Always non-null.</li>
     * </ul>
     *
     * @return list of discovered loggers
     */
    List<DiscoveredLogger> discover();

    /**
     * Set the level on a specific logger.
     *
     * @param loggerName the logger name
     * @param level      smplkit level string (e.g., {@code "DEBUG"}, {@code "INFO"}, {@code "WARN"})
     */
    void applyLevel(String loggerName, String level);

    /**
     * Install continuous discovery hook.
     *
     * <p>The callback should be invoked with {@code (loggerName, level)} whenever
     * a new logger is created in the framework. {@code level} is {@code null} when
     * the logger has no explicitly set level.</p>
     *
     * <p>May be a no-op if the framework doesn't support creation interception.</p>
     *
     * @param onNewLogger callback invoked with {@code (loggerName, level)} when a
     *     new logger is created; {@code level} is {@code null} when the logger
     *     has no explicitly set level
     */
    void installHook(BiConsumer<String, String> onNewLogger);

    /** Remove the hook installed by {@link #installHook}. Called on client {@code close()}. */
    void uninstallHook();
}
