package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Describes a logger to register via {@code client.logging.loggers.register}.
 *
 * <p>Used both for buffered runtime discovery (called by {@link com.smplkit.SmplClient}
 * as adapters discover loggers) and for explicit registration from setup scripts that
 * already know the {@code (service, environment)} they belong to.</p>
 *
 * @param name          Logger name (e.g. {@code "sqlalchemy.engine"}).  Normalized to lowercase
 *                      with slashes and colons replaced by dots before sending to the API.
 * @param resolvedLevel Effective log level for this source.
 * @param level         Explicit (configured) log level, if different from {@code resolvedLevel}.
 *                      Pass {@code null} when the level is inherited.
 * @param service       Service name this source belongs to (optional).
 * @param environment   Environment name this source belongs to (optional).
 */
public record LoggerSource(
        String name,
        LogLevel resolvedLevel,
        LogLevel level,
        String service,
        String environment
) {
    /** Convenience constructor without an explicit {@code level} (treated as inherited). */
    public LoggerSource(String name, LogLevel resolvedLevel, String service, String environment) {
        this(name, resolvedLevel, null, service, environment);
    }
}
