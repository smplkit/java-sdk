package com.smplkit.platform;

import java.time.Instant;

/**
 * Service resource (sync). Mutate fields, then call {@link #save()}.
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

    /**
     * Returns the stable, human-readable identifier for this service.
     *
     * @return the service id, or {@code null} for an unsaved instance
     */
    public String getId() { return id; }

    /**
     * Returns the display name shown in the Console.
     *
     * @return the display name
     */
    public String getName() { return name; }

    /**
     * Returns when this service was created. Set on instances returned by the
     * server; {@code null} for unsaved instances.
     *
     * @return the creation timestamp, or {@code null} if not yet saved
     */
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Returns when this service was last updated. Set on instances returned by
     * the server; {@code null} for unsaved instances.
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

    /** Create or update this service on the server. */
    public void save() {
        if (client == null) throw new IllegalStateException("Service was constructed without a client; cannot save");
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

    /** Delete this service from the server. */
    public void delete() {
        if (client == null || id == null) {
            throw new IllegalStateException("Service was constructed without a client or id; cannot delete");
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
