package com.smplkit.logging;

import com.smplkit.LogLevel;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A mutable log group resource from the Smpl Logging service.
 *
 * <p>Modify properties, then call {@link #save()} to persist changes.</p>
 */
public final class LogGroup {

    private LoggingClient client;
    private String id;
    private String name;
    private String level;
    private String group;
    private Map<String, Object> environments;
    private Instant createdAt;
    private Instant updatedAt;

    LogGroup(LoggingClient client, String id, String name,
             String level, String group, Map<String, Object> environments,
             Instant createdAt, Instant updatedAt) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.level = level;
        this.group = group;
        this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // --- Public getters ---

    public String getId() { return id; }
    public String getName() { return name; }
    public String getLevel() { return level; }
    public String getGroup() { return group; }
    public Map<String, Object> getEnvironments() { return environments; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // --- Public setters for mutable fields ---

    public void setName(String name) { this.name = name; }
    public void setGroup(String group) { this.group = group; }
    public void setEnvironments(Map<String, Object> environments) {
        this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
    }

    // --- Level convenience methods ---

    /** Set the base log level. */
    public void setLevel(LogLevel level) { this.level = level.getValue(); }

    /** Remove the base log level (inherit from parent group/ancestry). */
    public void clearLevel() { this.level = null; }

    /** Set the log level for a specific environment. */
    public void setEnvironmentLevel(String env, LogLevel level) {
        this.environments.put(env, Map.of("level", level.getValue()));
    }

    /** Remove the log level override for a specific environment. */
    public void clearEnvironmentLevel(String env) {
        this.environments.remove(env);
    }

    /** Remove all environment-level overrides. */
    public void clearAllEnvironmentLevels() {
        this.environments = new HashMap<>();
    }

    // --- Active record: save ---

    /**
     * Persists this log group to the server.
     *
     * <p>After a successful save, this instance is refreshed with the
     * server response.</p>
     */
    public void save() {
        if (client == null) throw new IllegalStateException("LogGroup not bound to a client");
        if (createdAt == null) {
            LogGroup created = client._createGroup(this);
            _apply(created);
        } else {
            LogGroup updated = client._updateGroup(this);
            _apply(updated);
        }
    }

    // --- Package-private setters (used by LoggingClient) ---

    void setId(String id) { this.id = id; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    void setClient(LoggingClient client) { this.client = client; }
    LoggingClient getClient() { return client; }

    /** Package-private: set level as raw string (used by LoggingClient). */
    void setLevelRaw(String level) { this.level = level; }

    void _apply(LogGroup other) {
        this.id = other.id;
        this.name = other.name;
        this.level = other.level;
        this.group = other.group;
        this.environments = other.environments != null ? new HashMap<>(other.environments) : new HashMap<>();
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    @Override
    public String toString() {
        return "LogGroup{id='" + id + "', name='" + name + "'}";
    }
}
