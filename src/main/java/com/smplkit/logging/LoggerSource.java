package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Describes a logger to register via
 * {@link LoggingManagement#registerSources(java.util.List)}.
 *
 * <p>Unlike auto-discovery (which reads the current process's logging framework),
 * {@code registerSources} accepts explicit {@code (service, environment)} overrides —
 * useful for sample-data seeding, cross-tenant migration, and test fixtures.</p>
 *
 * @param name          Logger name (e.g. {@code "com.example.MyService"}). Passed as-is.
 * @param service       Service name this source belongs to.
 * @param environment   Environment name this source belongs to.
 * @param resolvedLevel Effective log level for this source.
 * @param level         Explicit (configured) log level, if different from {@code resolvedLevel}.
 *                      Pass {@code null} when the level is inherited.
 */
public record LoggerSource(
        String name,
        String service,
        String environment,
        LogLevel resolvedLevel,
        LogLevel level
) {
    /** Convenience constructor without an explicit {@code level} (treated as inherited). */
    public LoggerSource(String name, String service, String environment, LogLevel resolvedLevel) {
        this(name, service, environment, resolvedLevel, null);
    }
}
