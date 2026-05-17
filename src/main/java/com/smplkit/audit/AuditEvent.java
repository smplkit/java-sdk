package com.smplkit.audit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Public-facing audit event resource — flat-named projection of the
 * JSON:API attributes shape returned by the audit service.
 *
 * <p>Snapshots, when recorded, live inside {@code data}. smplkit's internal
 * convention nests them at {@code data.get("snapshot")} but the shape is
 * unconstrained — customers may follow their own convention.</p>
 */
public final class AuditEvent {
    /** Server-assigned UUID for this event. */
    public final UUID id;
    /** Action slug — e.g. {@code "user.created"}, {@code "invoice.paid"}. */
    public final String action;
    /** Type of resource the action operated on — e.g. {@code "invoice"}. */
    public final String resourceType;
    /** Customer-facing id of the resource the action operated on. */
    public final String resourceId;
    /** When the action actually happened, as reported by the source. */
    public final OffsetDateTime occurredAt;
    /** When the audit service first ingested this event. */
    public final OffsetDateTime createdAt;
    /**
     * Type of the actor that performed the action ({@code "USER"},
     * {@code "API_KEY"}, {@code "SYSTEM"}, …). Empty string when unknown.
     */
    public final String actorType;
    /**
     * UUID of the actor when the actor is a tracked entity (user, api_key).
     * {@code null} for system actors or anonymous events.
     */
    public final UUID actorId;
    /**
     * Display label for the actor — typically a name or email. Empty
     * string when unknown.
     */
    public final String actorLabel;
    /**
     * Free-form per-event payload defined by the customer. Surfaced as a
     * structured JSONB column on the audit-event resource.
     */
    public final Map<String, Object> data;
    /**
     * Customer-supplied dedupe key. Empty when the customer didn't supply
     * one.
     */
    public final String idempotencyKey;
    /**
     * When {@code true}, the audit service skipped this event from SIEM
     * forwarder delivery regardless of any matching forwarder filter.
     */
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
