package com.smplkit.audit;

import java.time.OffsetDateTime;

/**
 * A distinct resource_type slug seen for the account.
 *
 * <p>The {@code id} and {@code resourceType} carry the same value —
 * JSON:API surfaces the customer-facing key as the resource id (ADR-014
 * "key as id"). Both fields are kept so SDK consumers can pick
 * whichever name reads better in context.</p>
 */
public final class AuditResourceType {
    public final String id;
    public final String resourceType;
    public final OffsetDateTime createdAt;

    public AuditResourceType(String id, String resourceType, OffsetDateTime createdAt) {
        this.id = id;
        this.resourceType = resourceType;
        this.createdAt = createdAt;
    }
}
