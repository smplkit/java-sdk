package com.smplkit.platform;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A context instance as returned by {@code client.platform.contexts.list()} and {@code .get()}.
 *
 * <p>The composite {@link #getId()} is {@code "type:key"}. Instances are active
 * records: mutate {@link #setName} / {@link #setAttributes}, then call
 * {@link #save()} to persist, or {@link #delete()} to remove. To create new
 * contexts in bulk, use {@link ContextsClient#register}.</p>
 */
public final class ContextEntity {

    private ContextsClient client;
    private final String type;
    private final String key;
    private String name;
    private Map<String, Object> attributes;
    private Instant createdAt;
    private Instant updatedAt;

    ContextEntity(ContextsClient client, String type, String key, String name,
                  Map<String, Object> attributes,
                  Instant createdAt, Instant updatedAt) {
        this.client = client;
        this.type = type;
        this.key = key;
        this.name = name;
        this.attributes = attributes != null ? Collections.unmodifiableMap(new HashMap<>(attributes)) : Collections.emptyMap();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Composite {@code "type:key"} identifier. */
    public String getId() { return type + ":" + key; }

    /**
     * Returns the context type (for example {@code "user"}).
     *
     * @return the context type
     */
    public String getType() { return type; }

    /**
     * Returns the context key — the type-scoped identifier for this instance.
     *
     * @return the context key
     */
    public String getKey() { return key; }

    /**
     * Returns the display name, or {@code null} if unset.
     *
     * @return the display name, or {@code null} when not set
     */
    public String getName() { return name; }

    /**
     * Returns this context's attributes as an unmodifiable map.
     *
     * @return an unmodifiable view of the context attributes
     */
    public Map<String, Object> getAttributes() { return attributes; }

    /**
     * Returns when this context was created, as reported by the server.
     *
     * @return the creation timestamp, or {@code null} if unavailable
     */
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Returns when this context was last updated, as reported by the server.
     *
     * @return the last-updated timestamp, or {@code null} if unavailable
     */
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Sets the display name. Local; call {@link #save()} to persist.
     *
     * @param name the new display name, or {@code null} to clear it
     */
    public void setName(String name) { this.name = name; }

    /**
     * Replaces this context's attributes. Local; call {@link #save()} to persist.
     *
     * @param attributes the new attribute map, copied defensively; {@code null}
     *     clears all attributes
     */
    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null
                ? Collections.unmodifiableMap(new HashMap<>(attributes))
                : Collections.emptyMap();
    }

    /** Persist this context to the server, applying the saved state back to this instance. */
    public void save() {
        if (client == null) {
            throw new IllegalStateException("Context was constructed without a client; cannot save");
        }
        _apply(client._save(this));
    }

    /** Async variant of {@link #save()}. */
    public java.util.concurrent.CompletableFuture<Void> saveAsync() {
        return saveAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Async variant of {@link #save()} with a custom executor. */
    public java.util.concurrent.CompletableFuture<Void> saveAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::save, executor);
    }

    /** Delete this context from the server. */
    public void delete() {
        if (client == null) {
            throw new IllegalStateException("Context was constructed without a client; cannot delete");
        }
        client.delete(getId());
    }

    /** Async variant of {@link #delete()}. */
    public java.util.concurrent.CompletableFuture<Void> deleteAsync() {
        return deleteAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Async variant of {@link #delete()} with a custom executor. */
    public java.util.concurrent.CompletableFuture<Void> deleteAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::delete, executor);
    }

    void _apply(ContextEntity other) {
        this.name = other.name;
        this.attributes = other.attributes;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    @Override
    public String toString() {
        return "ContextEntity{type='" + type + "', key='" + key + "'}";
    }
}
