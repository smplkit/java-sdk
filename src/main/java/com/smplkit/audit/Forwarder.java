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
 *
 * <p>{@code configuration} is the transport-specific shape discriminated
 * by {@code forwarderType}. Today every supported destination is an
 * HTTP-family transport so the field is a {@link HttpConfiguration}; as
 * other transports (FTP, SQS, …) land they will join the discriminated
 * union and {@code configuration} will widen.</p>
 *
 * <p>{@code transform} is interpreted according to {@code transformType}:
 * a JSONata expression (string) when {@code transformType} is
 * {@code JSONATA}; future engines may carry structured templates.</p>
 */
public final class Forwarder {
    public final UUID id;
    public final String name;
    public final String description; // nullable
    public final ForwarderType forwarderType;
    public final boolean enabled;
    public final Map<String, Object> filter; // nullable
    public final String transformType; // nullable
    public final Object transform; // nullable
    public final HttpConfiguration configuration;
    public final OffsetDateTime createdAt;
    public final OffsetDateTime updatedAt;
    public final OffsetDateTime deletedAt;
    public final Integer version;

    public Forwarder(UUID id, String name, String description, ForwarderType forwarderType,
                     boolean enabled, Map<String, Object> filter, String transformType,
                     Object transform, HttpConfiguration configuration,
                     OffsetDateTime createdAt, OffsetDateTime updatedAt,
                     OffsetDateTime deletedAt, Integer version) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.forwarderType = forwarderType;
        this.enabled = enabled;
        this.filter = filter;
        this.transformType = transformType;
        this.transform = transform;
        this.configuration = configuration;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        this.version = version;
    }
}
