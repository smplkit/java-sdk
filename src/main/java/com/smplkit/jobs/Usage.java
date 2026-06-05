package com.smplkit.jobs;

/** Current-period usage against the account's plan allotments (read-only). */
public final class Usage {
    /** The usage period this report covers, as {@code YYYY-MM} (UTC). */
    public final String period;
    /** Runs metered so far this period. */
    public final int runsUsed;
    /** Runs included in the plan this period ({@code -1} means unlimited). */
    public final int runsIncluded;
    /** Number of currently-enabled jobs. */
    public final int activeJobs;
    /** Maximum enabled jobs the plan allows ({@code -1} means unlimited). */
    public final int activeJobsLimit;

    Usage(String period, int runsUsed, int runsIncluded, int activeJobs, int activeJobsLimit) {
        this.period = period;
        this.runsUsed = runsUsed;
        this.runsIncluded = runsIncluded;
        this.activeJobs = activeJobs;
        this.activeJobsLimit = activeJobsLimit;
    }

    @Override
    public String toString() {
        return "Usage(period=" + period + ", runsUsed=" + runsUsed + "/" + runsIncluded + ")";
    }
}
