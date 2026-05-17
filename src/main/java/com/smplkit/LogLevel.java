package com.smplkit;

/**
 * Log severity levels used by the smplkit logging service.
 *
 * <p>Members are declared in alphabetical order. Severity ordering is
 * not derived from declaration order — see
 * {@code com.smplkit.logging.adapters.JulAdapter} for the canonical
 * smplkit ↔ {@code java.util.logging.Level} mapping.</p>
 */
public enum LogLevel {
    /** {@code DEBUG} severity. */
    DEBUG("DEBUG"),
    /** {@code ERROR} severity. */
    ERROR("ERROR"),
    /** {@code FATAL} severity. */
    FATAL("FATAL"),
    /** {@code INFO} severity. */
    INFO("INFO"),
    /** Disables emission. Used as a per-logger / per-environment ceiling. */
    SILENT("SILENT"),
    /** {@code TRACE} severity (lowest). */
    TRACE("TRACE"),
    /** {@code WARN} severity. */
    WARN("WARN");

    private final String value;

    LogLevel(String value) {
        this.value = value;
    }

    /** Returns the string representation of this level. */
    public String getValue() {
        return value;
    }
}
