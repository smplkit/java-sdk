package com.smplkit.audit;

import java.time.OffsetDateTime;

/**
 * A distinct action slug seen for the account.
 *
 * <p>Same shape as {@link AuditResourceType} — {@code id} and
 * {@code action} carry the same value. {@code createdAt} is the
 * earliest sighting; when the parent list call filtered by
 * {@code resourceType}, this is the first sighting of that specific
 * (action, resource_type) triple, not the action overall.</p>
 */
public final class AuditAction {
    public final String id;
    public final String action;
    public final OffsetDateTime createdAt;

    public AuditAction(String id, String action, OffsetDateTime createdAt) {
        this.id = id;
        this.action = action;
        this.createdAt = createdAt;
    }
}
