package com.smplkit.config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A mutable configuration resource from the Smpl Config service.
 *
 * <p>Instances are returned by {@link ConfigClient} methods and provide
 * a {@link #save()} method that creates or updates the resource on
 * the server.</p>
 *
 * <p>The {@code items} field stores the full typed shape from the API:
 * {@code {key: {value: v, type: t, description: d}}}. Use
 * {@link #getResolvedItems()} to extract plain {@code {key: rawValue}} values.</p>
 */
public final class Config {

    private ConfigClient client;
    private String id;          // null for unsaved
    private String key;
    private String name;
    private String description;
    private String parent;      // parent config UUID or null
    private Map<String, Object> items;  // {key: {value, type, description}} - the raw typed shape
    private Map<String, Object> environments;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Package-private constructor. Use {@link ConfigClient#new_(String)} to create instances.
     */
    Config(ConfigClient client, String key, String name) {
        this.client = client;
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.items = new HashMap<>();
        this.environments = new HashMap<>();
    }

    // --- Public getters ---

    /** Returns the unique identifier (UUID), or null for unsaved configs. */
    public String getId() { return id; }

    /** Returns the human-readable key (e.g. "user_service"). */
    public String getKey() { return key; }

    /** Returns the display name. */
    public String getName() { return name; }

    /** Returns the optional description (may be null). */
    public String getDescription() { return description; }

    /** Returns the parent config UUID, or null for root configs. */
    public String getParent() { return parent; }

    /**
     * Returns the full typed items map.
     *
     * <p>Each entry is {@code key -> {value: v, type: t, description: d}}.
     * Use {@link #getResolvedItems()} for plain {@code key -> rawValue}.</p>
     */
    public Map<String, Object> getItems() { return items; }

    /** Returns the environments map. */
    public Map<String, Object> getEnvironments() { return environments; }

    /** Returns the creation timestamp (may be null). */
    public Instant getCreatedAt() { return createdAt; }

    /** Returns the last-modified timestamp (may be null). */
    public Instant getUpdatedAt() { return updatedAt; }

    // --- Public setters (mutable fields) ---

    /** Sets the display name. */
    public void setName(String name) { this.name = name; }

    /** Sets the description. */
    public void setDescription(String description) { this.description = description; }

    /**
     * Sets items from a plain {@code {key: rawValue}} map.
     *
     * <p>Values are auto-wrapped into the typed shape
     * {@code {key: {value: rawValue}}}.</p>
     */
    public void setItems(Map<String, Object> items) {
        this.items = items != null ? new HashMap<>(items) : new HashMap<>();
    }

    /** Sets the environments map. */
    public void setEnvironments(Map<String, Object> environments) {
        this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
    }

    // --- Package-private setters (used by ConfigClient) ---

    void setId(String id) { this.id = id; }
    void setKey(String key) { this.key = key; }
    void setParent(String parent) { this.parent = parent; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    void setClient(ConfigClient client) { this.client = client; }
    ConfigClient getClient() { return client; }

    // --- Resolved items ---

    /**
     * Returns base values as plain {@code {key: rawValue}}.
     *
     * <p>Extracts the "value" field from each item definition. If an item
     * is already a plain value (not a map with "value" key), it is returned as-is.</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getResolvedItems() {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : items.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Map) {
                Map<String, Object> itemMap = (Map<String, Object>) v;
                if (itemMap.containsKey("value")) {
                    resolved.put(entry.getKey(), itemMap.get("value"));
                } else {
                    resolved.put(entry.getKey(), v);
                }
            } else {
                resolved.put(entry.getKey(), v);
            }
        }
        return resolved;
    }

    // --- Persistence ---

    /**
     * Persist this config to the server.
     *
     * <p>If {@code id} is null, creates a new config via POST.
     * Otherwise, updates the existing config via PUT.</p>
     *
     * <p>The server response is applied back into this instance via {@link #_apply(Config)}.</p>
     *
     * @throws IllegalStateException if not bound to a client
     */
    public void save() {
        if (client == null) throw new IllegalStateException("Config not bound to a client");
        if (id == null) {
            Config created = client._createConfig(this);
            _apply(created);
        } else {
            Config updated = client._updateConfig(this);
            _apply(updated);
        }
    }

    /**
     * Copy all properties from {@code other} into this instance.
     */
    void _apply(Config other) {
        this.id = other.id;
        this.key = other.key;
        this.name = other.name;
        this.description = other.description;
        this.parent = other.parent;
        this.items = new HashMap<>(other.items);
        this.environments = new HashMap<>(other.environments);
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    @Override
    public String toString() {
        return "Config[id=" + id + ", key=" + key + ", name=" + name + "]";
    }
}
