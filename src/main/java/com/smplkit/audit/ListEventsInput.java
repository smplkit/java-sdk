package com.smplkit.audit;

import java.util.List;

/**
 * Filters and pagination cursor for {@link AuditEvents#list(ListEventsInput)}.
 *
 * <p>Empty / null fields are left out of the request. Range syntax for
 * {@code occurredAtRange}, e.g. {@code [2026-01-01T00:00:00Z,*)}.</p>
 */
public final class ListEventsInput {
    /**
     * Environment keys to scope the results to. When non-empty, the keys are
     * sent as a comma-separated {@code filter[environment]} query parameter
     * (e.g. {@code ["production", "staging"]}). When {@code null} or empty the
     * filter is left unset, preserving the prior single-environment behavior.
     * The reserved value {@code "smplkit"} selects platform change events
     * smplkit records about your own resources.
     */
    public List<String> environments;
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
    /** Filter by category (exact match against whatever the recording call stored). */
    public String category;
    /** Range filter on {@code occurredAt}, e.g. {@code "[2026-01-01T00:00:00Z,*)"}. */
    public String occurredAtRange;
    /**
     * Optional free-text filter — returns only events whose
     * {@code resource_id} or {@code description} contains it as a
     * case-insensitive substring. Must be scoped (combine with
     * {@code occurredAtRange}, or with both {@code resourceType} and
     * {@code resourceId}) or the request is rejected. Null/unset to disable
     * text search.
     */
    public String search;
    /** Items per page; the server's max is honored. */
    public Integer pageSize;
    /** Opaque cursor returned by a prior page's {@code nextCursor}. */
    public String pageAfter;

    public ListEventsInput() {}
}
