package com.smplkit;

/**
 * Log levels for the smplkit logging service.
 *
 * <p>Maps to the canonical level strings used by the server API.</p>
 */
public enum LogLevel {
    TRACE("TRACE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    FATAL("FATAL"),
    SILENT("SILENT");

    private final String value;

    LogLevel(String value) {
        this.value = value;
    }

    /** Returns the canonical string representation used by the server. */
    public String getValue() {
        return value;
    }
}
