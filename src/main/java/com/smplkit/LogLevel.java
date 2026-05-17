package com.smplkit;

/**
 * Log severity levels used by the smplkit logging service.
 *
 * <p>Members are declared in increasing order of severity per Java
 * logging convention (SLF4J / Log4j / {@code java.util.logging}):
 * {@link #TRACE} (finest) → {@link #DEBUG} → {@link #INFO} →
 * {@link #WARN} → {@link #ERROR} → {@link #FATAL} → {@link #SILENT}
 * (disables emission). This means {@link #ordinal()} is a usable
 * severity index.</p>
 */
public enum LogLevel {
    /** {@code TRACE} severity (lowest / most verbose). */
    TRACE("TRACE"),
    /** {@code DEBUG} severity. */
    DEBUG("DEBUG"),
    /** {@code INFO} severity. */
    INFO("INFO"),
    /** {@code WARN} severity. */
    WARN("WARN"),
    /** {@code ERROR} severity. */
    ERROR("ERROR"),
    /** {@code FATAL} severity (highest). */
    FATAL("FATAL"),
    /** Disables emission. Used as a per-logger / per-environment ceiling. */
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
