package com.smplkit.audit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Public-facing audit event resource — flat-named projection of the
 * JSON:API attributes shape returned by the audit service. ADR-047 §2.3.1.
 *
 * <p>Snapshots, when recorded, live inside {@code data}. smplkit's internal
 * convention nests them at {@code data.get("snapshot")} but the shape is
 * unconstrained — customers may follow their own convention.
 */
public final class AuditEvent {
    public final UUID id;
    public final String action;
    public final String resourceType;
    public final String resourceId;
    public final OffsetDateTime occurredAt;
    public final OffsetDateTime createdAt;
    public final String actorType;
    public final UUID actorId; // nullable — null for API_KEY actor
    public final String actorLabel;
    public final Map<String, Object> data;
    public final String idempotencyKey;
    public final boolean doNotForward;

    public AuditEvent(UUID id, String action, String resourceType, String resourceId,
                      OffsetDateTime occurredAt, OffsetDateTime createdAt,
                      String actorType, UUID actorId, String actorLabel,
                      Map<String, Object> data,
                      String idempotencyKey, boolean doNotForward) {
        this.id = id;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.actorType = actorType;
        this.actorId = actorId;
        this.actorLabel = actorLabel;
        this.data = data;
        this.idempotencyKey = idempotencyKey;
        this.doNotForward = doNotForward;
    }
}
