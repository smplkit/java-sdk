package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A SIEM streaming forwarder configured on the customer's account.
 *
 * <p>Active-record style: mutate fields directly and call {@link #save()}
 * to persist, or {@link #delete()} to remove. Header values in
 * {@code configuration.headers} are always returned redacted on reads —
 * the GET path on the audit API replaces every header value with
 * {@code "<redacted>"}. Re-supply real values before calling {@link #save()}
 * (the SDK does not cache them client-side).</p>
 */
public final class Forwarder {

    private AuditForwarders client;

    /** Server-assigned UUID. {@code null} until {@link #save()} has run for the first time. */
    public UUID id;
    /** Display name. Free-form. */
    public String name;
    /** Optional free-text description. */
    public String description;
    /** Destination type — see {@link ForwarderType}. */
    public ForwarderType forwarderType;
    /**
     * When {@code false}, the audit service skips delivery for this
     * forwarder but still records {@code filtered_out} deliveries.
     */
    public boolean enabled = true;
    /**
     * Optional JSON Logic expression evaluated per event. When set, events
     * that don't match are recorded as {@code filtered_out} deliveries
     * instead of being POSTed to the destination.
     */
    public Map<String, Object> filter;
    /**
     * Optional template applied to each event before delivery. Shape
     * depends on {@link #transformType}; for {@link TransformType#JSONATA},
     * a JSONata expression string. {@code null} delivers the event JSON
     * as-is.
     */
    public String transform;
    /**
     * Engine used to evaluate {@link #transform}. Automatically set to
     * {@link TransformType#JSONATA} when {@link #transform} is set via
     * {@link AuditForwarders#newForwarder}.
     */
    public TransformType transformType;
    /** Destination request configuration. */
    public HttpConfiguration configuration;
    /** When the audit service first persisted this forwarder. {@code null} for an unsaved instance. */
    public OffsetDateTime createdAt;
    /** When this forwarder was last mutated. */
    public OffsetDateTime updatedAt;
    /** Soft-delete timestamp. {@code null} for live forwarders. */
    public OffsetDateTime deletedAt;
    /** Monotonic version counter; bumped on every server-side write. */
    public Integer version;

    Forwarder(AuditForwarders client, String name, ForwarderType forwarderType,
              HttpConfiguration configuration) {
        this.client = client;
        this.name = name;
        this.forwarderType = forwarderType;
        this.configuration = configuration;
    }

    Forwarder(AuditForwarders client, UUID id, String name, String description,
              ForwarderType forwarderType, boolean enabled, Map<String, Object> filter,
              TransformType transformType, String transform, HttpConfiguration configuration,
              OffsetDateTime createdAt, OffsetDateTime updatedAt,
              OffsetDateTime deletedAt, Integer version) {
        this.client = client;
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

    /**
     * Persists this forwarder to the server.
     *
     * <p>Upsert behavior is driven by {@link #createdAt}: a forwarder with
     * no {@code createdAt} is created (POST); otherwise it's full-replace
     * updated (PUT). After the call, every field is refreshed from the
     * server response (including newly-assigned {@code id},
     * {@code createdAt}, {@code updatedAt}, {@code version}).</p>
     */
    public void save() throws ApiException {
        if (client == null) {
            throw new IllegalStateException("Forwarder was constructed without a client; cannot save");
        }
        Forwarder other = (createdAt == null) ? client.create(this) : client.update(this);
        apply(other);
    }

    /** Soft-deletes this forwarder on the server. */
    public void delete() throws ApiException {
        if (client == null || id == null) {
            throw new IllegalStateException("Forwarder was constructed without a client or id; cannot delete");
        }
        client.delete(id);
    }

    void apply(Forwarder other) {
        this.id = other.id;
        this.name = other.name;
        this.description = other.description;
        this.forwarderType = other.forwarderType;
        this.enabled = other.enabled;
        this.filter = other.filter;
        this.transformType = other.transformType;
        this.transform = other.transform;
        this.configuration = other.configuration;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
        this.deletedAt = other.deletedAt;
        this.version = other.version;
    }
}
