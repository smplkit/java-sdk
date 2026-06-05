package com.smplkit.jobs;

/** Filters and cursor pagination for {@link RunsClient#list(ListRunsInput)}. */
public final class ListRunsInput {
    /** Filter to a single job's run history, by job id. {@code null} means no filter. */
    public String job;
    /** Items per page (cursor pagination). {@code null} uses the server default. */
    public Integer pageSize;
    /** Opaque cursor token from a prior page's {@code next} link. {@code null} starts at the first page. */
    public String after;
}
