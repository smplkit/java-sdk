package com.smplkit.platform;

import java.time.Instant;

/**
 * Environment resource (sync). Mutate fields, then call {@link #save()}.
 */
public final class Environment {

    private EnvironmentsClient client;
    private String id;
    private String name;
    private String color;
    private EnvironmentClassification classification;
    private boolean managed;
    private Instant createdAt;
    private Instant updatedAt;

    Environment(EnvironmentsClient client, String id, String name, String color,
                EnvironmentClassification classification, boolean managed,
                Instant createdAt, Instant updatedAt) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.color = color;
        this.classification = classification != null ? classification : EnvironmentClassification.STANDARD;
        this.managed = managed;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Returns the stable, human-readable identifier for this environment
     * (for example {@code "production"}).
     *
     * @return the environment id, or {@code null} for an unsaved instance
     */
    public String getId() { return id; }

    /**
     * Returns the display name shown in the Console.
     *
     * @return the display name
     */
    public String getName() { return name; }

    /**
     * Returns the raw hex color string. Use {@link #color()} for the typed accessor.
     *
     * @return the accent color as a CSS hex string, or {@code null} when unset
     */
    public String getColor() { return color; }

    /**
     * Returns the accent color as a typed {@link com.smplkit.Color}, or
     * {@code null} if unset.
     *
     * @return the accent color, or {@code null} when no color is set
     */
    public com.smplkit.Color color() {
        return color != null ? new com.smplkit.Color(color) : null;
    }

    /**
     * Returns whether this environment participates in the standard
     * environment ordering. Defaults to
     * {@link EnvironmentClassification#STANDARD}.
     *
     * @return the environment classification
     */
    public EnvironmentClassification getClassification() { return classification; }

    /**
     * Returns whether this environment is managed. Unmanaged environments
     * are view-only — per-environment resource values cannot be written
     * to them. {@code production} is always managed and cannot be demoted.
     *
     * @return {@code true} if this environment is managed
     */
    public boolean isManaged() { return managed; }

    /**
     * Returns when this environment was created. Set on instances returned by
     * the server; {@code null} for unsaved instances.
     *
     * @return the creation timestamp, or {@code null} if not yet saved
     */
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Returns when this environment was last updated. Set on instances returned
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

    /**
     * Sets the accent color from a raw hex string (validated via
     * {@link com.smplkit.Color}). Pass {@code null} to clear the color.
     *
     * @param color the accent color as a CSS hex string, or {@code null} for none
     */
    public void setColor(String color) {
        // Round-trip validation: reject malformed hex at the boundary.
        if (color != null) new com.smplkit.Color(color);
        this.color = color;
    }

    /**
     * Sets the accent color from a typed {@link com.smplkit.Color}. Pass
     * {@code null} to clear the color.
     *
     * @param color the accent color, or {@code null} for none
     */
    public void setColor(com.smplkit.Color color) {
        this.color = color != null ? color.hex() : null;
    }

    /**
     * Sets whether this environment participates in the standard environment
     * ordering.
     *
     * @param classification the new environment classification
     */
    public void setClassification(EnvironmentClassification classification) { this.classification = classification; }

    /**
     * Sets whether this environment is managed.
     *
     * @param managed {@code true} to mark this environment as managed
     */
    public void setManaged(boolean managed) { this.managed = managed; }

    /** Create or update this environment on the server. */
    public void save() {
        if (client == null) throw new IllegalStateException("Environment was constructed without a client; cannot save");
        Environment saved = createdAt == null ? client._create(this) : client._update(this);
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

    /** Delete this environment from the server. */
    public void delete() {
        if (client == null || id == null) {
            throw new IllegalStateException("Environment was constructed without a client or id; cannot delete");
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

    void _apply(Environment other) {
        this.id = other.id;
        this.name = other.name;
        this.color = other.color;
        this.classification = other.classification;
        this.managed = other.managed;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    @Override
    public String toString() {
        return "Environment{id='" + id + "', name='" + name + "', classification="
                + classification + ", managed=" + managed + "}";
    }
}
