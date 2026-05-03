package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Per-environment view of a logger or log group.
 *
 * <p>Immutable record; mirrors Python's {@code LoggerEnvironment}. Customer
 * code reads {@code logger.environments().get(envKey).level()} as a typed
 * {@link LogLevel}; mutations go through {@link Logger#setLevel(LogLevel, String)}
 * or {@link LogGroup#setLevel(LogLevel, String)}.</p>
 */
public record LoggerEnvironment(LogLevel level) {}
