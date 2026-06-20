package com.smplkit.jobs;

/**
 * How the wait between retries grows (a retry policy's backoff strategy).
 *
 * <ul>
 *   <li>{@link #EXPONENTIAL} — double the wait each retry
 *       ({@code delaySeconds}, then {@code 2×}, {@code 4×}, …), capped at
 *       {@code maxDelaySeconds}.</li>
 *   <li>{@link #FIXED} — wait a constant {@code delaySeconds} before every
 *       retry.</li>
 * </ul>
 *
 * <p>Members are declared in alphabetical order.</p>
 */
public enum Backoff {
    /** Double the wait each retry, capped at {@code maxDelaySeconds}. */
    EXPONENTIAL("exponential"),
    /** Wait a constant {@code delaySeconds} before every retry. */
    FIXED("fixed");

    private final String value;

    Backoff(String value) {
        this.value = value;
    }

    /** The wire-format slug — e.g. {@code "exponential"}. */
    public String getValue() {
        return value;
    }

    /** Parse a wire-format slug. Throws on unknown values. */
    public static Backoff fromValue(String value) {
        for (Backoff b : values()) {
            if (b.value.equals(value)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown Backoff: " + value);
    }
}
