package com.smplkit;

/**
 * Log levels for the smplkit logging service.
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

    /** Returns the string representation of this level. */
    public String getValue() {
        return value;
    }
}
