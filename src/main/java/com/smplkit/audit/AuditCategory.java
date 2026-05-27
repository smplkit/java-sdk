package com.smplkit.audit;

import java.time.OffsetDateTime;

/**
 * A distinct category value seen for the account.
 *
 * <p>The {@code id} and {@code category} carry the same value —
 * JSON:API surfaces the customer-facing key as the resource id (ADR-014
 * "key as id"). Both fields are kept so SDK consumers can pick
 * whichever name reads better in context.</p>
 */
public final class AuditCategory {
    /** The category value, surfaced as the JSON:API resource id. */
    public final String id;
    /** Same value as {@link #id}; provided for readability. */
    public final String category;
    /** Earliest sighting of this category for the account. */
    public final OffsetDateTime createdAt;

    public AuditCategory(String id, String category, OffsetDateTime createdAt) {
        this.id = id;
        this.category = category;
        this.createdAt = createdAt;
    }
}
