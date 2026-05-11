package com.smplkit.audit;

import java.util.UUID;

/** Filters and pagination for the per-forwarder delivery log. */
public final class ListDeliveriesInput {
    /**
     * One of {@code "SUCCEEDED"}, {@code "FAILED"}, {@code "FILTERED_OUT"},
     * {@code "SKIPPED_DO_NOT_FORWARD"} (case-insensitive). Null means no filter.
     */
    public String status;
    /**
     * Range notation per ADR-014, e.g.
     * {@code "[2026-01-01T00:00:00Z,*)"}.
     */
    public String createdAtRange;
    /** Filter deliveries to those for a specific event UUID. Null means no filter. */
    public UUID eventId;
    public Integer pageSize;
    public String pageAfter;
}
