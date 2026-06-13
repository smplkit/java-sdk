package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * A SIEM streaming forwarder configured on the customer's account.
 *
 * <p>Active-record style: mutate fields directly and call {@link #save()} to
 * persist, or {@link #delete()} to remove. Header values in
 * {@code configuration.headers} are returned in plaintext on reads, so
 * fetching a forwarder, mutating it, and calling {@link #save()} preserves
 * its header values without re-entering secrets.</p>
 *
 * <p>Both {@link #save()}/{@link #saveAsync()} and {@link #delete()}/{@link
 * #deleteAsync()} are exposed on the same instance — the async forms schedule
 * the round-trip on a {@link CompletableFuture}; the sync forms block.</p>
 */
public final class Forwarder {

    private AuditForwarders client;

    /**
     * Caller-supplied unique identifier (key) for this forwarder. Unique
     * within an account and immutable for the lifetime of the forwarder.
     * {@code null} only while the instance represents an unsaved instance
     * constructed without an id (which {@link #save()} would then reject).
     */
    public String id;
    /** Display name. Free-form. */
    public String name;
    /** Optional free-text description. */
    public String description;
    /** Destination type — see {@link ForwarderType}. */
    public ForwarderType forwarderType;
    /**
     * Read-only. Always {@code false} — the base enablement is pinned off.
     * Whether a forwarder actually delivers is decided per environment via
     * {@link #environments}; mutating this field has no effect on the server.
     */
    public boolean enabled = false;
    /**
     * Per-environment overrides keyed by environment key (e.g.
     * {@code "production"}, {@code "staging"}). A forwarder delivers in an
     * environment only when {@code environments.get(env).enabled} is
     * {@code true}. Each entry may carry an optional {@link HttpConfiguration}
     * override; omit it to inherit the base {@link #configuration}. Every
     * referenced environment must exist and be managed for the account.
     */
    public Map<String, ForwarderEnvironment> environments = new HashMap<>();
    /**
     * Optional JSON Logic expression evaluated per event. When set, events
     * that don't match are recorded as {@code filtered_out} deliveries
     * instead of being POSTed to the destination.
     */
    public Map<String, Object> filter;
    /**
     * Optional template applied to each event before delivery. Shape depends
     * on {@link #transformType}; for {@link TransformType#JSONATA}, a
     * {@code String} containing a JSONata expression. {@code null} delivers
     * the event JSON as-is.
     */
    public Object transform;
    /**
     * Engine used to evaluate {@link #transform}. Must be set whenever
     * {@link #transform} is set.
     */
    public TransformType transformType;
    /**
     * When {@code true}, this forwarder also receives platform change events
     * that smplkit records about your own resources (flag, configuration, and
     * similar changes). Each such event is delivered through every environment
     * this forwarder is enabled in, using that environment's resolved
     * configuration. Independent of the per-environment {@link #environments}
     * settings, since platform change events are not tied to a deployment
     * environment. Defaults to {@code false} — platform change events are not
     * forwarded unless you opt in.
     */
    public boolean forwardSmplkitEvents = false;
    /** Destination request configuration. */
    public HttpConfiguration configuration;
    /** When the audit service first persisted this forwarder. {@code null} for an unsaved instance. */
    public OffsetDateTime createdAt;
    /** When this forwarder was last mutated. */
    public OffsetDateTime updatedAt;
    /** Deletion timestamp; {@code null} for live forwarders. */
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

    @Override
    public String toString() {
        java.util.List<String> enabledEnvs = new java.util.ArrayList<>();
        for (Map.Entry<String, ForwarderEnvironment> e : environments.entrySet()) {
            if (e.getValue() != null && e.getValue().enabled) {
                enabledEnvs.add(e.getKey());
            }
        }
        java.util.Collections.sort(enabledEnvs);
        return "Forwarder(id=" + id + ", name=" + name + ", enabled_in=" + enabledEnvs + ")";
    }

    /**
     * Create or update this forwarder on the server.
     *
     * <p>Upsert behavior is driven by {@link #createdAt}: a forwarder with no
     * {@code createdAt} is created (POST); otherwise it's full-replace updated
     * (PUT). After the call, every field is refreshed from the server response
     * (including newly-assigned {@code id}, {@code createdAt},
     * {@code updatedAt}, {@code version}).</p>
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

    /** Create or full-replace this forwarder on the server, scheduled on the common pool. */
    public CompletableFuture<Void> saveAsync() {
        return saveAsync(ForkJoinPool.commonPool());
    }

    /** Create or full-replace this forwarder on the server, scheduled on {@code executor}. */
    public CompletableFuture<Void> saveAsync(Executor executor) {
        return CompletableFuture.runAsync(() -> {
            try {
                save();
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        }, executor);
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

    /** Delete this forwarder on the server. */
    public void delete() throws ApiException {
        if (client == null || id == null) {
            throw new IllegalStateException("Forwarder was constructed without a client or id; cannot delete");
        }
        client.delete(id);
    }

    /** Delete this forwarder on the server, scheduled on the common pool. */
    public CompletableFuture<Void> deleteAsync() {
        return deleteAsync(ForkJoinPool.commonPool());
    }

    /** Delete this forwarder on the server, scheduled on {@code executor}. */
    public CompletableFuture<Void> deleteAsync(Executor executor) {
        return CompletableFuture.runAsync(() -> {
            try {
                delete();
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Return the override for {@code environment}, creating an empty one if absent.
     *
     * <p>The per-environment mutators reach through here so an existing
     * override's other field is preserved when only one of {@code enabled} /
     * {@code configuration} is being set.</p>
     */
    private ForwarderEnvironment environmentOverride(String environment) {
        ForwarderEnvironment env = environments.get(environment);
        if (env == null) {
            env = new ForwarderEnvironment();
            environments.put(environment, env);
        }
        return env;
    }

    /**
     * Set this forwarder's destination configuration in memory (base).
     *
     * <p>Replaces the base {@link #configuration}. Call {@link #save()} to
     * persist.</p>
     */
    public void setConfiguration(HttpConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Set this forwarder's destination configuration in memory.
     *
     * <p>With {@code environment} omitted (the base overload), replaces the
     * base {@link #configuration}. With {@code environment} given, sets the
     * per-environment override's configuration on {@link #environments},
     * creating the override entry if it doesn't exist yet (preserving any
     * already-set {@code enabled} on it). Call {@link #save()} to persist.</p>
     */
    public void setConfiguration(HttpConfiguration configuration, String environment) {
        if (environment == null) {
            this.configuration = configuration;
        } else {
            environmentOverride(environment).configuration = configuration;
        }
    }

    /**
     * Set this forwarder's enablement in memory (base).
     *
     * <p>Sets the base {@link #enabled} (which the server pins false
     * regardless — enablement is per-environment). Call {@link #save()} to
     * persist.</p>
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Set this forwarder's enablement in memory.
     *
     * <p>With {@code environment} omitted (the base overload), sets the base
     * {@link #enabled} (which the server pins false regardless — enablement is
     * per-environment). With {@code environment} given, sets the
     * per-environment override's {@code enabled} on {@link #environments},
     * creating the override entry if it doesn't exist yet (preserving any
     * already-set {@code configuration} on it). Call {@link #save()} to
     * persist.</p>
     */
    public void setEnabled(boolean enabled, String environment) {
        if (environment == null) {
            this.enabled = enabled;
        } else {
            environmentOverride(environment).enabled = enabled;
        }
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
