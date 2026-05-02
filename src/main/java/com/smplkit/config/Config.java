package com.smplkit.config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A mutable configuration resource from the Smpl Config service.
 *
 * <p>Instances are returned by {@link ConfigClient} methods and provide
 * a {@link #save()} method to persist changes to the server.</p>
 *
 * <p>Use {@link #getResolvedItems()} to get plain {@code {key: value}} entries.</p>
 */
public final class Config {

    private ConfigClient client;
    private String id;
    private String name;
    private String description;
    private String parent;      // parent config slug or null
    private Map<String, Object> items;  // {key: {value, type, description}} - the raw typed shape
    private Map<String, Object> environments;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Package-private constructor. Use {@link ConfigClient#new_(String)} to create instances.
     */
    Config(ConfigClient client, String id, String name) {
        this.client = client;
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.items = new HashMap<>();
        this.environments = new HashMap<>();
    }

    // --- Public getters ---

    /** Returns the unique identifier (slug), or null for unsaved configs. */
    public String getId() { return id; }

    /** Returns the display name. */
    public String getName() { return name; }

    /** Returns the optional description (may be null). */
    public String getDescription() { return description; }

    /** Returns the parent config identifier, or null for root configs. */
    public String getParent() { return parent; }

    /**
     * Returns the items map with type metadata.
     *
     * <p>Use {@link #getResolvedItems()} for plain {@code key -> value} entries.</p>
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
     * Sets items from a plain {@code {key: value}} map.
     */
    public void setItems(Map<String, Object> items) {
        this.items = items != null ? new HashMap<>(items) : new HashMap<>();
    }

    /** Sets the environments map. */
    public void setEnvironments(Map<String, Object> environments) {
        this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
    }

    /** Sets the parent config id. */
    public void setParent(String parent) { this.parent = parent; }

    // --- Per-env unified setters (mirror Python's set_string / set_number / etc.) ---

    /** Sets a string item at the base level. */
    public void setString(String name, String value) {
        setString(name, value, null);
    }

    /** Sets a string item; {@code environment=null} sets the base, otherwise the per-env override. */
    @SuppressWarnings("unchecked")
    public void setString(String name, String value, String environment) {
        if (environment == null) {
            items.put(name, Map.of("value", value, "type", "STRING"));
        } else {
            Map<String, Object> env = (Map<String, Object>)
                    environments.computeIfAbsent(environment, k -> new HashMap<String, Object>());
            Map<String, Object> values = (Map<String, Object>)
                    env.computeIfAbsent("values", k -> new HashMap<String, Object>());
            values.put(name, Map.of("value", value, "type", "STRING"));
        }
    }

    /** Sets a number item at the base level. */
    public void setNumber(String name, Number value) {
        setNumber(name, value, null);
    }

    /** Sets a number item; {@code environment=null} sets the base. */
    @SuppressWarnings("unchecked")
    public void setNumber(String name, Number value, String environment) {
        if (environment == null) {
            items.put(name, Map.of("value", value, "type", "NUMBER"));
        } else {
            Map<String, Object> env = (Map<String, Object>)
                    environments.computeIfAbsent(environment, k -> new HashMap<String, Object>());
            Map<String, Object> values = (Map<String, Object>)
                    env.computeIfAbsent("values", k -> new HashMap<String, Object>());
            values.put(name, Map.of("value", value, "type", "NUMBER"));
        }
    }

    /** Sets a boolean item at the base level. */
    public void setBoolean(String name, boolean value) {
        setBoolean(name, value, null);
    }

    /** Sets a boolean item; {@code environment=null} sets the base. */
    @SuppressWarnings("unchecked")
    public void setBoolean(String name, boolean value, String environment) {
        if (environment == null) {
            items.put(name, Map.of("value", value, "type", "BOOLEAN"));
        } else {
            Map<String, Object> env = (Map<String, Object>)
                    environments.computeIfAbsent(environment, k -> new HashMap<String, Object>());
            Map<String, Object> values = (Map<String, Object>)
                    env.computeIfAbsent("values", k -> new HashMap<String, Object>());
            values.put(name, Map.of("value", value, "type", "BOOLEAN"));
        }
    }

    /** Sets a JSON item at the base level. */
    public void setJson(String name, Object value) {
        setJson(name, value, null);
    }

    /** Sets a JSON item; {@code environment=null} sets the base. */
    @SuppressWarnings("unchecked")
    public void setJson(String name, Object value, String environment) {
        if (environment == null) {
            items.put(name, Map.of("value", value, "type", "JSON"));
        } else {
            Map<String, Object> env = (Map<String, Object>)
                    environments.computeIfAbsent(environment, k -> new HashMap<String, Object>());
            Map<String, Object> values = (Map<String, Object>)
                    env.computeIfAbsent("values", k -> new HashMap<String, Object>());
            values.put(name, Map.of("value", value, "type", "JSON"));
        }
    }

    /** Removes an item; {@code environment=null} removes from base. */
    @SuppressWarnings("unchecked")
    public void remove(String name, String environment) {
        if (environment == null) {
            items.remove(name);
        } else {
            Map<String, Object> env = (Map<String, Object>) environments.get(environment);
            if (env != null) {
                Map<String, Object> values = (Map<String, Object>) env.get("values");
                if (values != null) values.remove(name);
            }
        }
    }

    /** Removes an item from the base. */
    public void remove(String name) {
        remove(name, null);
    }

    // --- Package-private setters (used by ConfigClient) ---

    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    void setClient(ConfigClient client) { this.client = client; }
    ConfigClient getClient() { return client; }

    // --- Resolved items ---

    /**
     * Returns item values as a plain {@code {key: value}} map.
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
     * Persists this config to the server.
     *
     * <p>After a successful save, this instance is refreshed with the
     * server response.</p>
     *
     * @throws IllegalStateException if not bound to a client
     */
    public void save() {
        if (client == null) throw new IllegalStateException("Config not bound to a client");
        if (createdAt == null) {
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
        return "Config[id=" + id + ", name=" + name + "]";
    }
}
