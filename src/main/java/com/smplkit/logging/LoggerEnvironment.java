package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Per-environment configuration on a logger or log group.
 *
 * <p>Lives at {@code logger.environments().get(envName)} (a
 * {@code Map<String, LoggerEnvironment>}). Immutable — mutate the override via
 * {@code logger.setLevel(level, environment)} or remove it via
 * {@code logger.clearLevel(environment)}.</p>
 *
 * <p>Attributes:</p>
 * <ul>
 *   <li>{@code level}: Per-environment level override ({@code null} means no override).</li>
 * </ul>
 *
 * @param level per-environment level override ({@code null} means no override)
 */
public record LoggerEnvironment(LogLevel level) {}
