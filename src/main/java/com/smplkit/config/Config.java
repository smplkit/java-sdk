package com.smplkit.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * A configuration resource fetched from the Smpl Config service.
 *
 * <p>Accessors:</p>
 * <ul>
 *   <li>{@link #getId()} — the config identifier (slug), or {@code null} for unsaved configs.</li>
 *   <li>{@link #getName()} — display name.</li>
 *   <li>{@link #getDescription()} — optional description.</li>
 *   <li>{@link #getParent()} — parent config id (slug), or {@code null} for root configs.</li>
 *   <li>{@link #items()} — base values as a {@code {key: value}} map.</li>
 *   <li>{@link #itemsRaw()} — full typed items as {@code {key: {value, type, description}}}.</li>
 *   <li>{@link #environments()} — map of environment names to their overrides.</li>
 *   <li>{@link #getCreatedAt()} — creation timestamp.</li>
 *   <li>{@link #getUpdatedAt()} — last-modified timestamp.</li>
 * </ul>
 */
public final class Config {

    private ConfigClient client;
    private String id;
    private String name;
    private String description;
    private String parent;
    // {key: {value, type, description}} — the raw typed shape.
    private Map<String, Object> itemsRaw;
    private Map<String, ConfigEnvironment> environments;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Full constructor. Use {@link ConfigClient#new_(String)} to create
     * instances in customer code; this is invoked by the client when
     * converting server resources.
     */
    Config(ConfigClient client, String id, String name, String description, String parent,
           Map<String, Object> items, Map<String, ConfigEnvironment> environments,
           Instant createdAt, Instant updatedAt) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.description = description;
        this.parent = parent;
        this.itemsRaw = items != null ? new HashMap<>(items) : new HashMap<>();
        this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // --- Public getters ---

    /** Returns the config identifier (slug), or {@code null} for unsaved configs. */
    public String getId() {
        return id;
    }

    /** Returns the display name. */
    public String getName() {
        return name;
    }

    /** Returns the optional description (may be {@code null}). */
    public String getDescription() {
        return description;
    }

    /** Returns the parent config id (slug), or {@code null} for root configs. */
    public String getParent() {
        return parent;
    }

    /** Returns the creation timestamp (may be {@code null}). */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Returns the last-modified timestamp (may be {@code null}). */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Read-only plain {@code {key: value}} view of base items.
     *
     * <p>Mutate via {@link #set} / {@link #setString} / {@link #setNumber} /
     * {@link #setBoolean} / {@link #setJson} / {@link #remove}.</p>
     *
     * @return a fresh map of item key to resolved value
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> items() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : itemsRaw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m && m.containsKey("value")) {
                out.put(e.getKey(), ((Map<String, Object>) m).get("value"));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /**
     * Return the full typed items {@code {key: {value, type, description}}}
     * (read-only deep copy).
     *
     * @return a fresh map of item key to its typed {@code {value, type,
     *     description}} entry
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> itemsRaw() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : itemsRaw.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v instanceof Map<?, ?> m ? new HashMap<>((Map<String, Object>) m) : v);
        }
        return out;
    }

    /**
     * Read-only view of per-environment overrides keyed by environment id.
     *
     * <p>Mutate via the {@code environment="..."} argument on {@link #set} /
     * {@link #setString} / {@link #setNumber} / {@link #setBoolean} /
     * {@link #setJson} / {@link #remove}.</p>
     *
     * @return a fresh map of environment id to its {@link ConfigEnvironment}
     *     overrides
     */
    public Map<String, ConfigEnvironment> environments() {
        return new HashMap<>(environments);
    }

    // --- Internal accessors used by the client / wire conversion ---

    /** Package-private: the live typed-items map ({@code {key: {value, type, description}}}). */
    Map<String, Object> rawItemsMap() {
        return itemsRaw;
    }

    /** Package-private: the live environments map. */
    Map<String, ConfigEnvironment> rawEnvironmentsMap() {
        return environments;
    }

    /**
     * Return the dict that {@code set()} / {@code remove()} should mutate.
     *
     * <p>For the base config this is the typed-items map storing
     * {@code {key: {value, type, description}}}. For an environment override
     * it is the flat overrides map storing {@code {key: rawValue}}.</p>
     */
    private Map<String, Object> itemsTarget(String environment) {
        if (environment == null) {
            return itemsRaw;
        }
        ConfigEnvironment env = environments.get(environment);
        if (env == null) {
            env = new ConfigEnvironment();
            environments.put(environment, env);
        }
        return env.rawMap();
    }

    // --- Setters / mutators ---

    /**
     * Sets the display name.
     *
     * @param name the new display name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Sets the description.
     *
     * @param description the new description, or {@code null} to clear it
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the parent config id.
     *
     * @param parent the parent config id (slug) to inherit from, or {@code null}
     *     to make this a root config
     */
    public void setParent(String parent) {
        this.parent = parent;
    }

    /**
     * Set (or replace) an item.  When {@code environment} is given, sets an override on that environment.
     *
     * <p>When {@code environment} is supplied, the override carries only the raw
     * value — the declared {@code type} / {@code description} come from the base
     * item, so the {@code ConfigItem}'s type and description are ignored.</p>
     *
     * @param item        the {@link ConfigItem} to set. Its name is the item key.
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void set(ConfigItem item, String environment) {
        if (environment == null) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("value", item.value());
            raw.put("type", item.type().value());
            if (item.description() != null) {
                raw.put("description", item.description());
            }
            itemsTarget(null).put(item.name(), raw);
        } else {
            itemsTarget(environment).put(item.name(), item.value());
        }
    }

    /**
     * Set (or replace) a base-level item.
     *
     * @param item the {@link ConfigItem} to set. Its name is the item key.
     */
    public void set(ConfigItem item) {
        set(item, null);
    }

    /**
     * Remove an item by name.  When {@code environment} is given, removes the per-environment override only.
     *
     * <p>Removing an item that isn't present is a no-op.</p>
     *
     * @param name        the item key to remove
     * @param environment when non-{@code null}, remove only this environment's
     *     override for {@code name}, leaving the base item intact
     */
    public void remove(String name, String environment) {
        itemsTarget(environment).remove(name);
    }

    /**
     * Remove a base-level item by name. Removing an item that isn't present is a no-op.
     *
     * @param name the item key to remove
     */
    public void remove(String name) {
        remove(name, null);
    }

    /**
     * Convenience: set a base-level STRING item.
     *
     * @param name  the item key to set
     * @param value the string value
     */
    public void setString(String name, String value) {
        set(new ConfigItem(name, value, ItemType.STRING), null);
    }

    /**
     * Convenience: set a STRING item (or environment override).
     *
     * @param name        the item key to set
     * @param value       the string value
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void setString(String name, String value, String environment) {
        set(new ConfigItem(name, value, ItemType.STRING), environment);
    }

    /**
     * Convenience: set a STRING item with a description (or environment override).
     *
     * @param name        the item key to set
     * @param value       the string value
     * @param description optional human-readable description. Ignored when setting
     *     an environment override.
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void setString(String name, String value, String description, String environment) {
        set(new ConfigItem(name, value, ItemType.STRING, description), environment);
    }

    /**
     * Convenience: set a base-level NUMBER item.
     *
     * @param name  the item key to set
     * @param value the numeric value
     */
    public void setNumber(String name, Number value) {
        set(new ConfigItem(name, value, ItemType.NUMBER), null);
    }

    /**
     * Convenience: set a NUMBER item (or environment override).
     *
     * @param name        the item key to set
     * @param value       the numeric value
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void setNumber(String name, Number value, String environment) {
        set(new ConfigItem(name, value, ItemType.NUMBER), environment);
    }

    /**
     * Convenience: set a NUMBER item with a description (or environment override).
     *
     * @param name        the item key to set
     * @param value       the numeric value
     * @param description optional human-readable description. Ignored when setting
     *     an environment override.
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void setNumber(String name, Number value, String description, String environment) {
        set(new ConfigItem(name, value, ItemType.NUMBER, description), environment);
    }

    /**
     * Convenience: set a base-level BOOLEAN item.
     *
     * @param name  the item key to set
     * @param value the boolean value
     */
    public void setBoolean(String name, boolean value) {
        set(new ConfigItem(name, value, ItemType.BOOLEAN), null);
    }

    /**
     * Convenience: set a BOOLEAN item (or environment override).
     *
     * @param name        the item key to set
     * @param value       the boolean value
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void setBoolean(String name, boolean value, String environment) {
        set(new ConfigItem(name, value, ItemType.BOOLEAN), environment);
    }

    /**
     * Convenience: set a BOOLEAN item with a description (or environment override).
     *
     * @param name        the item key to set
     * @param value       the boolean value
     * @param description optional human-readable description. Ignored when setting
     *     an environment override.
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void setBoolean(String name, boolean value, String description, String environment) {
        set(new ConfigItem(name, value, ItemType.BOOLEAN, description), environment);
    }

    /**
     * Convenience: set a base-level JSON item.
     *
     * @param name  the item key to set
     * @param value any JSON-serializable value (map, list, or primitive)
     */
    public void setJson(String name, Object value) {
        set(new ConfigItem(name, value, ItemType.JSON), null);
    }

    /**
     * Convenience: set a JSON item (or environment override).
     *
     * @param name        the item key to set
     * @param value       any JSON-serializable value (map, list, or primitive)
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void setJson(String name, Object value, String environment) {
        set(new ConfigItem(name, value, ItemType.JSON), environment);
    }

    /**
     * Convenience: set a JSON item with a description (or environment override).
     *
     * @param name        the item key to set
     * @param value       any JSON-serializable value (map, list, or primitive)
     * @param description optional human-readable description. Ignored when setting
     *     an environment override.
     * @param environment when non-{@code null}, set the value as an override on
     *     this environment rather than on the base config
     */
    public void setJson(String name, Object value, String description, String environment) {
        set(new ConfigItem(name, value, ItemType.JSON, description), environment);
    }

    // --- Persistence ---

    /**
     * Persist this config to the server.
     *
     * <p>Creates a new config if unsaved, or updates the existing one.</p>
     *
     * @throws com.smplkit.errors.NotFoundError   If the config no longer exists (update).
     * @throws com.smplkit.errors.ValidationError If the server rejects the request.
     * @throws IllegalStateException              If the model was constructed without a client.
     */
    public void save() {
        if (client == null) {
            throw new IllegalStateException("Config was constructed without a client; cannot save");
        }
        Config other;
        if (createdAt == null) {
            other = client._createConfig(this);
        } else {
            other = client._updateConfigFromModel(this);
        }
        apply(other);
    }

    /**
     * Async variant of {@link #save()}, scheduled on the JDK common pool.
     *
     * <p>The customer picks sync vs async at the call site on the same model.
     * Provide a custom executor via {@link #saveAsync(Executor)}.</p>
     *
     * @return a future that completes when the config has been persisted, or
     *     completes exceptionally with the same errors as {@link #save()}
     */
    public CompletableFuture<Void> saveAsync() {
        return saveAsync(ForkJoinPool.commonPool());
    }

    /**
     * Async variant of {@link #save()} with a custom executor.
     *
     * @param executor the executor to run the persistence call on
     * @return a future that completes when the config has been persisted, or
     *     completes exceptionally with the same errors as {@link #save()}
     */
    public CompletableFuture<Void> saveAsync(Executor executor) {
        return CompletableFuture.runAsync(this::save, executor);
    }

    /**
     * Delete this config from the server.
     *
     * @throws IllegalStateException if the model was constructed without a client
     *     or has no id
     */
    public void delete() {
        if (client == null || id == null) {
            throw new IllegalStateException("Config was constructed without a client or id; cannot delete");
        }
        client.delete(id);
    }

    /**
     * Async variant of {@link #delete()}, scheduled on the JDK common pool.
     *
     * @return a future that completes when the config has been deleted
     */
    public CompletableFuture<Void> deleteAsync() {
        return deleteAsync(ForkJoinPool.commonPool());
    }

    /**
     * Async variant of {@link #delete()} with a custom executor.
     *
     * @param executor the executor to run the delete call on
     * @return a future that completes when the config has been deleted
     */
    public CompletableFuture<Void> deleteAsync(Executor executor) {
        return CompletableFuture.runAsync(this::delete, executor);
    }

    /** Copy all properties from {@code other} into this instance. */
    private void apply(Config other) {
        this.id = other.id;
        this.name = other.name;
        this.description = other.description;
        this.parent = other.parent;
        this.itemsRaw = other.itemsRaw;
        this.environments = other.environments;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    /**
     * Walk the parent chain and return config data entries child-to-root.
     *
     * @param configs Optional pre-fetched list of configs to look up parents
     *     by ID, avoiding extra network calls.
     */
    List<Resolver.ChainEntry> buildChain(List<Config> configs) {
        List<Resolver.ChainEntry> chain = new ArrayList<>();
        chain.add(toChainEntry(this));
        Config current = this;
        Map<String, Config> configsById = new HashMap<>();
        if (configs != null) {
            for (Config c : configs) {
                if (c.id != null) {
                    configsById.put(c.id, c);
                }
            }
        }
        while (current.parent != null) {
            Config parentConfig = configsById.get(current.parent);
            if (parentConfig == null) {
                if (client == null) {
                    throw new IllegalStateException(
                            "cannot resolve parent config '" + current.parent + "' without a client");
                }
                parentConfig = client.get(current.parent);
            }
            chain.add(toChainEntry(parentConfig));
            current = parentConfig;
        }
        return chain;
    }

    /** Build a {@link Resolver.ChainEntry} from a config's typed items + flat environments. */
    private static Resolver.ChainEntry toChainEntry(Config config) {
        Map<String, Map<String, Object>> envWire = new HashMap<>();
        for (Map.Entry<String, ConfigEnvironment> e : config.environments.entrySet()) {
            envWire.put(e.getKey(), e.getValue().values());
        }
        return new Resolver.ChainEntry(
                config.id != null ? config.id : "",
                new HashMap<>(config.itemsRaw),
                envWire);
    }

    @Override
    public String toString() {
        return "Config(id=" + id + ", name=" + name + ")";
    }
}
