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
    public String action;
    public String resourceType;
    public String resourceId;
    /** Optional. Defaults to server-side now() if null. */
    public OffsetDateTime occurredAt;
    /** Optional full resource snapshot (ADR-047 §2.5). */
    public Map<String, Object> snapshot;
    /** Optional contextual extras (request id, IP, etc.). */
    public Map<String, Object> data;
    /** Optional. Server derives a content hash if null. */
    public String idempotencyKey;
    /**
     * When true, the audit service records the event normally but does NOT
     * POST it through any configured SIEM forwarder. A
     * {@code skipped_do_not_forward} delivery row is recorded for each
     * enabled forwarder so the skip is visible in the delivery log.
     */
    public boolean doNotForward;

    public CreateEventInput() {}

    public CreateEventInput(String action, String resourceType, String resourceId) {
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }
}
