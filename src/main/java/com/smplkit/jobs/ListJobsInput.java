package com.smplkit.jobs;

/** Filters and pagination for {@link JobsClient#list(ListJobsInput)}. */
public final class ListJobsInput {
    /** Filter to recurring ({@code true}) or one-off ({@code false}) jobs. {@code null} means no filter. */
    public Boolean recurring;
    /** Filter to jobs whose name contains this text (case-insensitive). {@code null} means no filter. */
    public String name;
    /** 1-based page number to return. Defaults to 1 server-side when null. */
    public Integer pageNumber;
    /** Items per page (1–1000). Defaults to 1000 server-side when null. */
    public Integer pageSize;
}
