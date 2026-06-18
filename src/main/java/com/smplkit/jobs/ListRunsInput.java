package com.smplkit.jobs;

import java.util.List;

/** Filters and cursor pagination for {@link RunsClient#list(ListRunsInput)}. */
public final class ListRunsInput {
    /** Filter to a single job's run history, by job id. {@code null} means no filter. */
    public String job;
    /**
     * Restrict to runs stamped with any of these environment keys, sent
     * comma-separated as {@code filter[environment]}. {@code null} or empty
     * falls back to the client's configured environment (if any), otherwise
     * covers every environment you can access.
     */
    public List<String> environments;
    /** Items per page (cursor pagination). {@code null} uses the server default. */
    public Integer pageSize;
    /** Opaque cursor token from a prior page's {@code next} link. {@code null} starts at the first page. */
    public String after;
}
