package com.smplkit.audit;

/**
 * Filters and pagination cursor for {@link AuditEvents#list(ListEventsInput)}.
 *
 * <p>Empty / null fields are unset on the wire. ADR-014 range syntax for
 * {@code occurredAtRange}, e.g. {@code [2026-01-01T00:00:00Z,*)}.</p>
 */
public final class ListEventsInput {
    /** Filter by event type slug — e.g. {@code "user.created"}. */
    public String eventType;
    /** Filter by resource type — e.g. {@code "invoice"}. */
    public String resourceType;
    /** Filter by resource id (exact match). */
    public String resourceId;
    /** Filter by actor type. Matches the literal string stored on the event. */
    public String actorType;
    /** Filter by actor id. Matches the literal string stored on the event — any identifier scheme works. */
    public String actorId;
    /** Range filter on {@code occurredAt}, e.g. {@code "[2026-01-01T00:00:00Z,*)"}. */
    public String occurredAtRange;
    /**
     * Freeform substring search ({@code filter[search]}, ADR-014).
     * Case-insensitive substring match against {@code resource_id} on the
     * audit service at this revision; future expansion is non-breaking
     * under the ADR. Use {@link #resourceId} for exact-match instead.
     */
    public String search;
    /**
     * Restrict results to events whose {@code doNotForward} flag matches the
     * given boolean. Forwarder previews typically pass {@code false} to match
     * live-pipeline semantics (events flagged {@code doNotForward=true} are
     * skipped by the forwarder pipeline). {@code null} leaves the filter unset.
     */
    public Boolean doNotForward;
    /** Items per page; the server's max is honored. */
    public Integer pageSize;
    /** Opaque cursor returned by a prior page's {@code nextCursor}. */
    public String pageAfter;

    public ListEventsInput() {}
}
