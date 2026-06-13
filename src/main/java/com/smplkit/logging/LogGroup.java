package com.smplkit.logging;

import com.smplkit.LogLevel;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * SDK model for a log group resource.
 *
 * <p>Modify properties locally, then call {@link #save} to persist.</p>
 */
public final class LogGroup {

    private LogGroupsClient client;
    private String id;
    private String name;
    private String level;
    private String group;
    private Map<String, Object> environments;
    private Instant createdAt;
    private Instant updatedAt;

    LogGroup(LogGroupsClient client, String id, String name,
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

    /** @return the log group's identifier, or null when unsaved. */
    public String getId() { return id; }

    /** @return the log group's display name. */
    public String getName() { return name; }

    /** @return the base log level, or null when inherited. */
    public String getLevel() { return level; }

    /** @return the id of the parent log group, or null for a top-level group. */
    public String getGroup() { return group; }

    /** @return when this group was created on the server, or null when unsaved. */
    public Instant getCreatedAt() { return createdAt; }

    /** @return when this group was last updated on the server, or null when unsaved. */
    public Instant getUpdatedAt() { return updatedAt; }

    /** Raw per-environment map. Internal — use {@link #environments()} for the typed view. */
    Map<String, Object> getEnvironments() { return environments; }

    /**
     * Read-only view of per-environment level overrides.
     *
     * <p>Mutate via {@link #setLevel} / {@link #clearLevel} /
     * {@link #clearAllEnvironmentLevels} (with an {@code environment} argument).</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, LoggerEnvironment> environments() {
        Map<String, LoggerEnvironment> out = new HashMap<>();
        for (Map.Entry<String, Object> e : environments.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> envMap = (Map<String, Object>) e.getValue();
            Object levelStr = envMap.get("level");
            LogLevel lvl = null;
            if (levelStr instanceof String s) {
                try {
                    lvl = LogLevel.valueOf(s);
                } catch (IllegalArgumentException ignored) {
                    /* unknown level */
                }
            }
            out.put(e.getKey(), new LoggerEnvironment(lvl));
        }
        return Map.copyOf(out);
    }

    // --- Public setters for mutable fields ---

    /**
     * Set the group's display name. Local until {@link #save()}.
     *
     * @param name the display name
     */
    public void setName(String name) { this.name = name; }

    /**
     * Set the id of the parent log group, or null for a top-level group.
     * Local until {@link #save()}.
     *
     * @param group the parent log-group id, or null
     */
    public void setGroup(String group) { this.group = group; }

    // --- Level convenience methods ---

    /**
     * Set the log level.
     *
     * <p>Sets the base log level used when no environment-specific override
     * applies.</p>
     */
    public void setLevel(LogLevel level) {
        setLevel(level, null);
    }

    /**
     * Set the log level.
     *
     * <p>With {@code environment=null} (the default), sets the base log level used
     * when no environment-specific override applies.  With an {@code environment},
     * sets the per-environment override.</p>
     *
     * <p>Changes are local until you call {@link #save()}.</p>
     *
     * @param level       the log level to apply
     * @param environment when given, set the override for that environment only;
     *     when null, set the base level
     */
    public void setLevel(LogLevel level, String environment) {
        if (environment == null) {
            this.level = level != null ? level.getValue() : null;
        } else {
            Map<String, Object> override = new java.util.HashMap<>();
            override.put("level", level != null ? level.getValue() : null);
            this.environments.put(environment, override);
        }
    }

    /**
     * Remove a log level.
     *
     * <p>Removes the base log level (the group then inherits from its parent
     * group / dot-notation ancestor / system default).</p>
     */
    public void clearLevel() {
        clearLevel(null);
    }

    /**
     * Remove a log level.
     *
     * <p>With {@code environment=null} (the default), removes the base log level
     * (the group then inherits from its parent group / dot-notation ancestor /
     * system default).  With an {@code environment}, removes the per-environment
     * override only.</p>
     *
     * <p>Changes are local until you call {@link #save()}.</p>
     *
     * @param environment when given, remove the override for that environment
     *     only; when null, remove the base level
     */
    public void clearLevel(String environment) {
        if (environment == null) {
            this.level = null;
        } else {
            this.environments.remove(environment);
        }
    }

    /** Remove all per-environment level overrides. */
    public void clearAllEnvironmentLevels() {
        this.environments = new HashMap<>();
    }

    // --- Active record: save / delete ---

    /** Persist this group to the server (create or update). */
    public void save() {
        if (client == null) {
            throw new IllegalStateException("LogGroup was constructed without a client; cannot save");
        }
        LogGroup updated = client.saveGroup(this);
        applyFrom(updated);
    }

    /**
     * Persist this group to the server on the common pool.
     *
     * @return a future that completes when the create-or-update round-trip finishes
     */
    public CompletableFuture<Void> saveAsync() {
        return saveAsync(ForkJoinPool.commonPool());
    }

    /**
     * Persist this group to the server on the given executor.
     *
     * @param executor the executor that runs the persist round-trip
     * @return a future that completes when the create-or-update round-trip finishes
     */
    public CompletableFuture<Void> saveAsync(Executor executor) {
        return CompletableFuture.runAsync(this::save, executor);
    }

    /** Delete this group from the server. */
    public void delete() {
        if (client == null || id == null) {
            throw new IllegalStateException("LogGroup was constructed without a client or id; cannot delete");
        }
        client.delete(id);
    }

    /**
     * Delete this group from the server on the common pool.
     *
     * @return a future that completes when the delete round-trip finishes
     */
    public CompletableFuture<Void> deleteAsync() {
        return deleteAsync(ForkJoinPool.commonPool());
    }

    /**
     * Delete this group from the server on the given executor.
     *
     * @param executor the executor that runs the delete round-trip
     * @return a future that completes when the delete round-trip finishes
     */
    public CompletableFuture<Void> deleteAsync(Executor executor) {
        return CompletableFuture.runAsync(this::delete, executor);
    }

    // --- Package-private accessors (used by LoggingClient / sub-clients) ---

    void setId(String id) { this.id = id; }
    void setLevelRaw(String level) { this.level = level; }
    void setClient(LogGroupsClient client) { this.client = client; }

    /** Copy all properties from {@code other} into {@code this}. */
    void applyFrom(LogGroup other) {
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
        return "LogGroup(id=" + id + ", name=" + name + ")";
    }
}
