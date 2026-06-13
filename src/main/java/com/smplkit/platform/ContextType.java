package com.smplkit.platform;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Context-type resource (sync). Mutate fields, then call {@link #save()}.
 */
public final class ContextType {

    private ContextTypesClient client;
    private String id;
    private String name;
    private Map<String, Map<String, Object>> attributes;
    private Instant createdAt;
    private Instant updatedAt;

    ContextType(ContextTypesClient client, String id, String name,
                Map<String, Map<String, Object>> attributes,
                Instant createdAt, Instant updatedAt) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Returns the stable, human-readable identifier for this context type
     * (for example {@code "user"}).
     *
     * @return the context-type id, or {@code null} for an unsaved instance
     */
    public String getId() { return id; }

    /**
     * Returns the display name shown in the Console.
     *
     * @return the display name
     */
    public String getName() { return name; }

    /**
     * Returns the declared known-attribute slots, keyed by attribute name, each
     * mapped to its metadata. The returned map is unmodifiable; use
     * {@link #addAttribute}, {@link #updateAttribute}, and
     * {@link #removeAttribute} to change the slots.
     *
     * @return an unmodifiable view of the known-attribute slots
     */
    public Map<String, Map<String, Object>> getAttributes() { return Collections.unmodifiableMap(attributes); }

    /**
     * Returns when this context type was created. Set on instances returned by
     * the server; {@code null} for unsaved instances.
     *
     * @return the creation timestamp, or {@code null} if not yet saved
     */
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Returns when this context type was last updated. Set on instances returned
     * by the server; {@code null} for unsaved instances.
     *
     * @return the last-updated timestamp, or {@code null} if not yet saved
     */
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Sets the display name shown in the Console.
     *
     * @param name the new display name
     */
    public void setName(String name) { this.name = name; }

    /** Add a known-attribute slot. Local; call {@link #save()} to persist. */
    public void addAttribute(String name, Object... metadata) {
        Map<String, Object> meta = new HashMap<>();
        for (int i = 0; i + 1 < metadata.length; i += 2) {
            meta.put(String.valueOf(metadata[i]), metadata[i + 1]);
        }
        attributes.put(name, meta);
    }

    /** Add a known-attribute slot. Local; call {@link #save()} to persist. */
    public void addAttribute(String name, Map<String, Object> metadata) {
        attributes.put(name, metadata != null ? new HashMap<>(metadata) : new HashMap<>());
    }

    /** Add a known-attribute slot. Local; call {@link #save()} to persist. */
    public void addAttribute(String name) {
        attributes.put(name, new HashMap<>());
    }

    /** Remove a known-attribute slot. Local; call {@link #save()} to persist. */
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    /** Replace a known-attribute slot's metadata. Local; call {@link #save()}. */
    public void updateAttribute(String name, Map<String, Object> metadata) {
        attributes.put(name, metadata != null ? new HashMap<>(metadata) : new HashMap<>());
    }

    /** Create or update this context type on the server. */
    public void save() {
        if (client == null) throw new IllegalStateException("ContextType was constructed without a client; cannot save");
        ContextType saved = createdAt == null ? client._create(this) : client._update(this);
        _apply(saved);
    }

    /** Async variant of {@link #save()}. */
    public java.util.concurrent.CompletableFuture<Void> saveAsync() {
        return saveAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Async variant of {@link #save()} with a custom executor. */
    public java.util.concurrent.CompletableFuture<Void> saveAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::save, executor);
    }

    /** Delete this context type from the server. */
    public void delete() {
        if (client == null || id == null) {
            throw new IllegalStateException("ContextType was constructed without a client or id; cannot delete");
        }
        client.delete(id);
    }

    /** Async variant of {@link #delete()}. */
    public java.util.concurrent.CompletableFuture<Void> deleteAsync() {
        return deleteAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Async variant of {@link #delete()} with a custom executor. */
    public java.util.concurrent.CompletableFuture<Void> deleteAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::delete, executor);
    }

    void _apply(ContextType other) {
        this.id = other.id;
        this.name = other.name;
        this.attributes = other.attributes != null ? new HashMap<>(other.attributes) : new HashMap<>();
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    @Override
    public String toString() {
        return "ContextType{id='" + id + "', name='" + name + "'}";
    }
}
