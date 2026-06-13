package com.smplkit.audit;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Input for {@link AuditEvents#record(CreateEventInput)}.
 *
 * <p>{@code resourceType} starting with {@code smpl.} is reserved for
 * smplkit-emitted events; the server returns 403 for customer attempts.</p>
 */
public final class CreateEventInput {
    /** Event type slug — e.g. {@code "user.created"}, {@code "invoice.paid"}. */
    public String eventType;
    /** Type of resource the event operated on — e.g. {@code "invoice"}. */
    public String resourceType;
    /** Customer-facing id of the resource the event operated on. */
    public String resourceId;
    /** Optional free-form bucket label. {@code null} round-trips as {@code null} on read. */
    public String category;
    /** Optional; defaults to server-side {@code now()} if {@code null}. */
    public OffsetDateTime occurredAt;
    /**
     * Free-form label for the kind of actor that caused the event (e.g.
     * {@code "USER"}, {@code "API_KEY"}, {@code "SYSTEM"}, or any custom
     * value). The audit service never backfills this from the request
     * credential — set it explicitly when you want the event attributed.
     */
    public String actorType;
    /** Free-form identifier of the actor. Any string scheme is accepted. */
    public String actorId;
    /** Human-readable label for the actor (e.g. email or API key name). */
    public String actorLabel;
    /**
     * Optional contextual extras. To record a resource snapshot, nest it
     * inside {@code data} — smplkit's internal convention is
     * {@code data.put("snapshot", ...)}, but the shape is unconstrained.
     */
    public Map<String, Object> data;
    /** Optional; the server derives a content hash when {@code null}. */
    public String idempotencyKey;
    /**
     * When {@code true}, the audit service records the event normally but
     * does NOT POST it through any configured SIEM forwarder. A
     * {@code skipped_do_not_forward} delivery row is recorded for each
     * enabled forwarder so the skip is visible in the delivery log.
     */
    public boolean doNotForward;

    public CreateEventInput() {}

    /**
     * @param eventType event type slug — e.g. {@code "user.created"}
     * @param resourceType type of resource the event operated on — e.g.
     *     {@code "invoice"}; values starting with {@code smpl.} are reserved
     *     and rejected by the server with a 403
     * @param resourceId customer-facing id of the resource the event operated on
     */
    public CreateEventInput(String eventType, String resourceType, String resourceId) {
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }
}
