package com.smplkit.management;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context-type resource. Defines targeting-rule entity schemas.
 *
 * <p>Mutate attributes via {@link #addAttribute}, {@link #removeAttribute},
 * {@link #updateAttribute}, then call {@link #save()} to persist.</p>
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

    public String getId() { return id; }
    public String getName() { return name; }
    public Map<String, Map<String, Object>> getAttributes() { return Collections.unmodifiableMap(attributes); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }

    /** Add or replace a known-attribute slot. Local — call {@link #save()} to persist. */
    public void addAttribute(String name, Object... metadata) {
        Map<String, Object> meta = new HashMap<>();
        for (int i = 0; i + 1 < metadata.length; i += 2) {
            meta.put(String.valueOf(metadata[i]), metadata[i + 1]);
        }
        attributes.put(name, meta);
    }

    /** Add a known-attribute slot with metadata map. Local — call {@link #save()} to persist. */
    public void addAttribute(String name, Map<String, Object> metadata) {
        attributes.put(name, metadata != null ? new HashMap<>(metadata) : new HashMap<>());
    }

    /** Add a known-attribute slot with no metadata. Local — call {@link #save()} to persist. */
    public void addAttribute(String name) {
        attributes.put(name, new HashMap<>());
    }

    /** Remove a known-attribute slot. Local — call {@link #save()} to persist. */
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    /** Replace a known-attribute slot's metadata. Local — call {@link #save()} to persist. */
    public void updateAttribute(String name, Map<String, Object> metadata) {
        attributes.put(name, metadata != null ? new HashMap<>(metadata) : new HashMap<>());
    }

    /**
     * Creates or updates this context type on the server. Applies the server response back.
     */
    public void save() {
        if (client == null) throw new IllegalStateException("ContextType not bound to a client");
        ContextType saved = createdAt == null ? client._create(this) : client._update(this);
        _apply(saved);
    }

    /** Async variant of {@link #save()} (rule 12). */
    public java.util.concurrent.CompletableFuture<Void> saveAsync() {
        return saveAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Async variant of {@link #save()} with a custom executor. */
    public java.util.concurrent.CompletableFuture<Void> saveAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::save, executor);
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
