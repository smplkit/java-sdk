package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Event fired when a logger's effective level changes.
 *
 * @param key    the logger key that changed
 * @param level  the new effective log level
 * @param source the change source ({@code "start"}, {@code "refresh"}, or {@code "websocket"})
 */
public record LoggerChangeEvent(String key, LogLevel level, String source) {
}
