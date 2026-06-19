package com.smplkit.jobs;

/** Filters and pagination for {@link JobsClient#list(ListJobsInput)}. */
public final class ListJobsInput {
    /**
     * Restrict to a single {@link JobKind}. {@code null} lists recurring and
     * manual jobs; one-off jobs are omitted unless {@link JobKind#ONE_OFF} is
     * set.
     */
    public JobKind kind;
    /**
     * Filter to jobs that have an upcoming fire in some environment
     * ({@code true}) or none ({@code false}) — the feed for an upcoming-runs
     * view, which includes one-offs. {@code null} means no filter.
     */
    public Boolean scheduled;
    /** Filter to jobs whose name contains this text (case-insensitive). {@code null} means no filter. */
    public String name;
    /** 1-based page number to return. Defaults to 1 server-side when null. */
    public Integer pageNumber;
    /** Items per page (1–1000). Defaults to 1000 server-side when null. */
    public Integer pageSize;
}
