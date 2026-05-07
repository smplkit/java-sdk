package com.smplkit.audit;

/** Filters and pagination for the per-forwarder delivery log. */
public final class ListDeliveriesInput {
    /**
     * One of {@code "succeeded"}, {@code "failed"}, {@code "filtered_out"},
     * {@code "skipped_do_not_forward"}. Null means no filter.
     */
    public String status;
    /**
     * Range notation per ADR-014, e.g.
     * {@code "[2026-01-01T00:00:00Z,*)"}.
     */
    public String createdAtRange;
    public Integer pageSize;
    public String pageAfter;
}
