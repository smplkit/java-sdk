package com.smplkit.jobs;

/**
 * How a job runs, derived from its schedule (read-only).
 *
 * <ul>
 *   <li>{@link #MANUAL} — no schedule; never auto-fires; runs only when
 *       triggered.</li>
 *   <li>{@link #ONE_OFF} — a {@code "now"} or datetime schedule; runs a single
 *       time, then is spent.</li>
 *   <li>{@link #RECURRING} — a cron schedule; fires on a repeating cadence.</li>
 * </ul>
 *
 * <p>Members are declared in alphabetical order.</p>
 */
public enum JobKind {
    /** No schedule — never auto-fires; runs only when triggered. */
    MANUAL("manual"),
    /** A {@code "now"} or datetime schedule — runs a single time, then is spent. */
    ONE_OFF("one_off"),
    /** A cron schedule — fires on a repeating cadence. */
    RECURRING("recurring");

    private final String value;

    JobKind(String value) {
        this.value = value;
    }

    /** The wire-format slug — e.g. {@code "recurring"}. */
    public String getValue() {
        return value;
    }

    /** Parse a wire-format slug. Throws on unknown values. */
    public static JobKind fromValue(String value) {
        for (JobKind k : values()) {
            if (k.value.equals(value)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Unknown JobKind: " + value);
    }
}
