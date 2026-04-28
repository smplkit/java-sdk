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
    public String getColor() { return color; }
    public EnvironmentClassification getClassification() { return classification; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setColor(String color) { this.color = color; }
    public void setClassification(EnvironmentClassification classification) { this.classification = classification; }

    /**
     * Creates or updates this environment on the server. Applies the server response back.
     */
    public void save() {
        if (client == null) throw new IllegalStateException("Environment not bound to a client");
        Environment saved = createdAt == null ? client._create(this) : client._update(this);
        _apply(saved);
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
