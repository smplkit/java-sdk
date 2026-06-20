package com.smplkit.jobs;

/**
 * A failure category a retry policy can retry on (see {@link RetryOn}).
 *
 * <ul>
 *   <li>{@link #CONNECTION_ERROR} — the endpoint could not be reached.</li>
 *   <li>{@link #NON_SUCCESS_STATUS} — any non-success response, regardless of
 *       {@link RetryOn#statuses}.</li>
 *   <li>{@link #TIMEOUT} — the run did not complete in time.</li>
 * </ul>
 *
 * <p>Members are declared in alphabetical order.</p>
 */
public enum RetryReason {
    /** The endpoint could not be reached. */
    CONNECTION_ERROR("CONNECTION_ERROR"),
    /** Any non-success response, regardless of {@link RetryOn#statuses}. */
    NON_SUCCESS_STATUS("NON_SUCCESS_STATUS"),
    /** The run did not complete in time. */
    TIMEOUT("TIMEOUT");

    private final String value;

    RetryReason(String value) {
        this.value = value;
    }

    /** The wire-format slug — e.g. {@code "TIMEOUT"}. */
    public String getValue() {
        return value;
    }

    /** Parse a wire-format slug. Throws on unknown values. */
    public static RetryReason fromValue(String value) {
        for (RetryReason r : values()) {
            if (r.value.equals(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown RetryReason: " + value);
    }
}
