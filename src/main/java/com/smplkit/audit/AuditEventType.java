package com.smplkit.audit;

import java.time.OffsetDateTime;

/**
 * A distinct event type slug seen for the account.
 *
 * <p>Same shape as {@link AuditResourceType} — {@code id} and
 * {@code eventType} carry the same value. {@code createdAt} is the
 * earliest sighting; when the parent list call filtered by
 * {@code resourceType}, this is the first sighting of that specific
 * (event_type, resource_type) pair, not the event type overall.</p>
 */
public final class AuditEventType {
    /** The event type slug, surfaced as the JSON:API resource id. */
    public final String id;
    /** Same value as {@link #id}; provided for readability. */
    public final String eventType;
    /**
     * Earliest sighting of this event type (or event_type/resource_type pair when
     * the list call was filtered) for the account.
     */
    public final OffsetDateTime createdAt;

    public AuditEventType(String id, String eventType, OffsetDateTime createdAt) {
        this.id = id;
        this.eventType = eventType;
        this.createdAt = createdAt;
    }
}
