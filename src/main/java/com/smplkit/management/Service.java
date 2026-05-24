package com.smplkit.management;

import java.time.Instant;

/**
 * Mutable service resource. Mutate fields, then call {@link #save()} to persist.
 *
 * <p>A service represents a backend application or microservice in the
 * customer's stack that contexts can be evaluated against.</p>
 */
public final class Service {

    private ServicesClient client;
    private String id;
    private String name;
    private Instant createdAt;
    private Instant updatedAt;

    Service(ServicesClient client, String id, String name,
            Instant createdAt, Instant updatedAt) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }

    /**
     * Creates or updates this service on the server. Applies the server response back.
     */
    public void save() {
        if (client == null) throw new IllegalStateException("Service not bound to a client");
        Service saved = createdAt == null ? client._create(this) : client._update(this);
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

    /** Deletes this service from the server. */
    public void delete() {
        if (client == null || id == null) {
            throw new IllegalStateException("Service not bound to a client or has no id");
        }
        client.delete(id);
    }

    void _apply(Service other) {
        this.id = other.id;
        this.name = other.name;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    @Override
    public String toString() {
        return "Service{id='" + id + "', name='" + name + "'}";
    }
}
