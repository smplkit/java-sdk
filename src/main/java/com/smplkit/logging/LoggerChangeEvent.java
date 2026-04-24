package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Event fired when a logger's effective level changes.
 *
 * @param id      the logger id that changed
 * @param level   the new effective log level (may be {@code null} for delete events)
 * @param source  the change source (e.g. {@code "start"}, {@code "refresh"})
 * @param deleted {@code true} if the logger or group was deleted
 */
public record LoggerChangeEvent(String id, LogLevel level, String source, boolean deleted) {

    /** Convenience constructor for non-delete events. */
    public LoggerChangeEvent(String id, LogLevel level, String source) {
        this(id, level, source, false);
    }

    /** Returns {@code true} if the logger was deleted. */
    public boolean isDeleted() {
        return deleted;
    }
}
