package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Event fired when a logger's effective level changes.
 *
 * @param id     the logger id that changed
 * @param level  the new effective log level
 * @param source the change source (e.g. {@code "start"}, {@code "refresh"})
 */
public record LoggerChangeEvent(String id, LogLevel level, String source) {
}
