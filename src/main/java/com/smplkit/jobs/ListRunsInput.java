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
    /**
     * Restrict to runs started by any of these triggers (see {@link RunTrigger}),
     * sent comma-joined as {@code filter[trigger]} (any-of) — e.g.
     * {@code [RunTrigger.RETRY]} for automatic retries. {@code null} or empty
     * covers every trigger.
     */
    public List<RunTrigger> triggers;
    /**
     * When {@code true}, collapse the result to the last completed (succeeded /
     * failed / canceled) run per job-and-environment; in-flight runs are
     * excluded and the other filters apply first. Sent as {@code last_run_only}
     * only when {@code true} — a {@code false} value is omitted from the wire.
     */
    public boolean lastRunOnly = false;
    /** Items per page (cursor pagination). {@code null} uses the server default. */
    public Integer pageSize;
    /** Opaque cursor token from a prior page's {@code next} link. {@code null} starts at the first page. */
    public String after;
}
