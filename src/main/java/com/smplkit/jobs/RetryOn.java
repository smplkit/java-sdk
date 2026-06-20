package com.smplkit.jobs;

import java.util.ArrayList;
import java.util.List;

/**
 * Which failures a retry policy retries.
 *
 * <p>An empty {@link RetryOn} (both lists empty) retries nothing.</p>
 */
public final class RetryOn {

    /**
     * Response status codes to retry when a run fails because the response did
     * not match the job's success status (e.g. {@code [429, 503]} for rate-limit
     * and unavailable). Each is a 3-digit HTTP code. Empty matches no status.
     */
    public List<Integer> statuses = new ArrayList<>();

    /**
     * Failure categories to retry — see {@link RetryReason}. Empty matches no
     * reason.
     */
    public List<RetryReason> reasons = new ArrayList<>();

    /** An empty {@link RetryOn} (both lists empty) that retries nothing. */
    public RetryOn() {}

    /**
     * @param statuses response status codes to retry; {@code null} starts empty
     * @param reasons failure categories to retry; {@code null} starts empty
     */
    public RetryOn(List<Integer> statuses, List<RetryReason> reasons) {
        this.statuses = statuses != null ? new ArrayList<>(statuses) : new ArrayList<>();
        this.reasons = reasons != null ? new ArrayList<>(reasons) : new ArrayList<>();
    }
}
