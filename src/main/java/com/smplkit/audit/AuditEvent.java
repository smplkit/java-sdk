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
    /** Event type slug — e.g. {@code "user.created"}, {@code "invoice.paid"}. */
    public final String eventType;
    /** Type of resource the event operated on — e.g. {@code "invoice"}. */
    public final String resourceType;
    /** Customer-facing id of the resource the event operated on. */
    public final String resourceId;
    /** Optional free-form bucket label. {@code null} when not supplied. */
    public final String category;
    /** When the event actually happened, as reported by the source. */
    public final OffsetDateTime occurredAt;
    /** When the audit service first ingested this event. */
    public final OffsetDateTime createdAt;
    /**
     * Free-form label for the kind of actor that caused the event
     * (e.g. {@code "USER"}, {@code "API_KEY"}, {@code "SYSTEM"}, or any
     * custom value). {@code null} when not supplied — the audit service
     * never backfills from the request credential.
     */
    public final String actorType;
    /**
     * Identifier of the actor. Free-form string — any identifier scheme
     * is accepted, including non-UUID values. {@code null} when not
     * supplied.
     */
    public final String actorId;
    /**
     * Human-readable label for the actor (e.g. an email address or API
     * key name). {@code null} when not supplied.
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
    /**
     * The environment the event was recorded in. Read-only and always
     * present on reads — the audit service resolves it when the event is
     * recorded (from a single-environment credential, or from the runtime
     * SDK's configured environment, which the SDK sends on the recording
     * request body). {@code null} only for an event that predates
     * environment scoping.
     */
    public final String environment;

    public AuditEvent(UUID id, String eventType, String resourceType, String resourceId,
                      String category,
                      OffsetDateTime occurredAt, OffsetDateTime createdAt,
                      String actorType, String actorId, String actorLabel,
                      Map<String, Object> data,
                      String idempotencyKey, boolean doNotForward, String environment) {
        this.id = id;
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.category = category;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.actorType = actorType;
        this.actorId = actorId;
        this.actorLabel = actorLabel;
        this.data = data;
        this.idempotencyKey = idempotencyKey;
        this.doNotForward = doNotForward;
        this.environment = environment;
    }
}
