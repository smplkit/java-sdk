package com.smplkit.management;

import java.time.Instant;

/**
 * Mutable environment resource. Mutate fields, then call {@link #save()} to persist.
 */
public final class Environment {

    private EnvironmentsClient client;
    private String id;
    private String name;
    private String color;
    private EnvironmentClassification classification;
    private Instant createdAt;
    private Instant updatedAt;

    Environment(EnvironmentsClient client, String id, String name, String color,
                EnvironmentClassification classification, Instant createdAt, Instant updatedAt) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.color = color;
        this.classification = classification != null ? classification : EnvironmentClassification.STANDARD;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    /** Returns the raw hex color string. Use {@link #color()} for the typed accessor. */
    public String getColor() { return color; }

    /**
     * Returns the color as a typed {@link com.smplkit.Color}, or null if unset.
     *
     * <p>Mirrors Python rule 9 ({@code Environment.color} is a {@code Color} instance,
     * not a raw string) — the wire still transports a hex string; the SDK wraps and
     * unwraps at the boundary.</p>
     */
    public com.smplkit.Color color() {
        return color != null ? new com.smplkit.Color(color) : null;
    }

    public EnvironmentClassification getClassification() { return classification; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }

    /** Sets the color from a raw hex string (validated via {@link com.smplkit.Color}). */
    public void setColor(String color) {
        // Round-trip validation: reject malformed hex at the boundary.
        if (color != null) new com.smplkit.Color(color);
        this.color = color;
    }

    /** Sets the color from a typed {@link com.smplkit.Color}. */
    public void setColor(com.smplkit.Color color) {
        this.color = color != null ? color.hex() : null;
    }

    public void setClassification(EnvironmentClassification classification) { this.classification = classification; }

    /**
     * Creates or updates this environment on the server. Applies the server response back.
     */
    public void save() {
        if (client == null) throw new IllegalStateException("Environment not bound to a client");
        Environment saved = createdAt == null ? client._create(this) : client._update(this);
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

    void _apply(Environment other) {
        this.id = other.id;
        this.name = other.name;
        this.color = other.color;
        this.classification = other.classification;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    @Override
    public String toString() {
        return "Environment{id='" + id + "', name='" + name + "', classification=" + classification + "}";
    }
}
