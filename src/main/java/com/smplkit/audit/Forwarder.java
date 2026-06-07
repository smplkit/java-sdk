package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

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

    /**
     * Caller-supplied key for this forwarder (used as the JSON:API
     * {@code data.id} and as the URL path-param). For a new instance built
     * via {@link AuditForwarders#newForwarder}, this is the id passed in
     * by the caller. May only be {@code null} for the (rare) case of a
     * forwarder constructed without the active-record builders.
     */
    public String id;
    /** Display name. Free-form. */
    public String name;
    /** Optional free-text description. */
    public String description;
    /** Destination type — see {@link ForwarderType}. */
    public ForwarderType forwarderType;
    /**
     * Read-only. Always {@code false} — the base enablement is pinned off
     * server-side. Whether a forwarder actually delivers is decided per
     * environment via {@link #environments}; mutating this field has no
     * effect on the server.
     */
    public boolean enabled = false;
    /**
     * Per-environment overrides keyed by environment key (e.g.
     * {@code "production"}, {@code "staging"}). A forwarder delivers in an
     * environment only when {@code environments.get(env).enabled} is
     * {@code true}. Each entry may carry an optional
     * {@link HttpConfiguration} override; omit it (leave
     * {@code configuration} null) to inherit the base
     * {@link #configuration}. Every referenced environment must exist and
     * be managed for the account.
     */
    public Map<String, ForwarderEnvironment> environments = new HashMap<>();
    /**
     * Optional JSON Logic expression evaluated per event. When set, events
     * that don't match are recorded as {@code filtered_out} deliveries
     * instead of being POSTed to the destination.
     */
    public Map<String, Object> filter;
    /**
     * Optional template applied to each event before delivery. Shape
     * depends on {@link #transformType}; for {@link TransformType#JSONATA},
     * a {@code String} containing the JSONata expression. {@code null}
     * delivers the event JSON as-is.
     *
     * <p>Typed as {@code Object} because future engines may carry
     * structured templates rather than plain strings; the wire model
     * accepts any JSON-serializable value.</p>
     */
    public Object transform;
    /**
     * Engine used to evaluate {@link #transform}. Must be set whenever
     * {@link #transform} is set, and vice versa — {@link #save()} rejects
     * a forwarder where exactly one of the two is set.
     */
    public TransformType transformType;
    /**
     * When {@code true}, this forwarder also receives smplkit's own platform
     * change events (flag/config/etc. changes), delivered through every
     * environment it is enabled in. Defaults to {@code false}. This is a
     * base-level forwarder setting, parallel to {@link #filter} and
     * {@link #transform} — not part of the per-environment
     * {@link #environments} override map.
     */
    public boolean forwardSmplkitEvents = false;
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

    Forwarder(AuditForwarders client, String id, String name, ForwarderType forwarderType,
              HttpConfiguration configuration) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.forwarderType = forwarderType;
        this.configuration = configuration;
    }

    Forwarder(AuditForwarders client, String id, String name, String description,
              ForwarderType forwarderType, boolean enabled,
              Map<String, ForwarderEnvironment> environments, Map<String, Object> filter,
              TransformType transformType, Object transform, boolean forwardSmplkitEvents,
              HttpConfiguration configuration,
              OffsetDateTime createdAt, OffsetDateTime updatedAt,
              OffsetDateTime deletedAt, Integer version) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.description = description;
        this.forwarderType = forwarderType;
        this.enabled = enabled;
        this.environments = environments != null ? environments : new HashMap<>();
        this.filter = filter;
        this.transformType = transformType;
        this.transform = transform;
        this.forwardSmplkitEvents = forwardSmplkitEvents;
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
     *
     * @throws IllegalArgumentException if {@code transform} and
     *     {@code transformType} are not both set or both unset, or if
     *     {@code transformType} is {@link TransformType#JSONATA} and
     *     {@code transform} is not a {@code String}
     */
    public void save() throws ApiException {
        if (client == null) {
            throw new IllegalStateException("Forwarder was constructed without a client; cannot save");
        }
        validateTransform(transformType, transform);
        Forwarder other = (createdAt == null) ? client.create(this) : client.update(this);
        apply(other);
    }

    static void validateTransform(TransformType transformType, Object transform) {
        if ((transform == null) != (transformType == null)) {
            throw new IllegalArgumentException(
                    "transform and transformType must both be set or both be unset");
        }
        if (transformType == TransformType.JSONATA && !(transform instanceof String)) {
            throw new IllegalArgumentException(
                    "transform must be a String when transformType is JSONATA");
        }
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
        this.environments = other.environments;
        this.filter = other.filter;
        this.transformType = other.transformType;
        this.transform = other.transform;
        this.forwardSmplkitEvents = other.forwardSmplkitEvents;
        this.configuration = other.configuration;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
        this.deletedAt = other.deletedAt;
        this.version = other.version;
    }
}
