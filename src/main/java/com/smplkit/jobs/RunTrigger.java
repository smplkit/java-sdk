package com.smplkit.jobs;

/**
 * What started a run (read-only).
 *
 * <p>{@link Run#trigger} is the raw wire string; compare it against these
 * constants' {@link #getValue()} — e.g.
 * {@code RunTrigger.MANUAL.getValue().equals(run.trigger)}.</p>
 *
 * <ul>
 *   <li>{@link #MANUAL} — a {@code run}/{@code trigger} call started it on
 *       demand.</li>
 *   <li>{@link #RERUN} — it repeats an earlier run.</li>
 *   <li>{@link #SCHEDULE} — the job's schedule fired.</li>
 * </ul>
 *
 * <p>Members are declared in alphabetical order.</p>
 */
public enum RunTrigger {
    /** A {@code run}/{@code trigger} call started it on demand. */
    MANUAL("MANUAL"),
    /** It repeats an earlier run. */
    RERUN("RERUN"),
    /** The job's schedule fired. */
    SCHEDULE("SCHEDULE");

    private final String value;

    RunTrigger(String value) {
        this.value = value;
    }

    /** The wire-format slug — e.g. {@code "MANUAL"}. */
    public String getValue() {
        return value;
    }

    /** Parse a wire-format slug. Throws on unknown values. */
    public static RunTrigger fromValue(String value) {
        for (RunTrigger t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown RunTrigger: " + value);
    }
}
