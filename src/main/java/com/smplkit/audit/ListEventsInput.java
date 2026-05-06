package com.smplkit.audit;

/**
 * Filters and pagination cursor for {@link AuditEvents#list(ListEventsInput)}.
 *
 * <p>Empty / null fields are unset on the wire. ADR-014 range syntax for
 * {@code occurredAtRange}, e.g. {@code [2026-01-01T00:00:00Z,*)}.</p>
 */
public final class ListEventsInput {
    public String action;
    public String resourceType;
    public String resourceId;
    public String actorType;
    public String actorId; // UUID string
    public String occurredAtRange;
    public Integer pageSize;
    public String pageAfter;

    public ListEventsInput() {}
}
