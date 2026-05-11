package com.smplkit.audit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * SIEM streaming forwarder configured on the customer's account.
 *
 * <p>Header values returned on reads are always redacted — re-supply
 * real values when calling
 * {@link AuditForwarders#update(UUID, CreateForwarderInput)}.</p>
 */
public final class Forwarder {
    public final UUID id;
    public final String name;
    public final String slug;
    public final ForwarderType forwarderType;
    public final boolean enabled;
    public final Map<String, Object> filter; // nullable
    public final String transform; // nullable
    public final ForwarderHttp http;
    public final OffsetDateTime createdAt;
    public final OffsetDateTime updatedAt;
    public final OffsetDateTime deletedAt;
    public final Integer version;

    public Forwarder(UUID id, String name, String slug, ForwarderType forwarderType, boolean enabled,
                     Map<String, Object> filter, String transform, ForwarderHttp http,
                     OffsetDateTime createdAt, OffsetDateTime updatedAt,
                     OffsetDateTime deletedAt, Integer version) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.forwarderType = forwarderType;
        this.enabled = enabled;
        this.filter = filter;
        this.transform = transform;
        this.http = http;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        this.version = version;
    }
}
