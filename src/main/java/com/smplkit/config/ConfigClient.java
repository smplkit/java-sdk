package com.smplkit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smplkit.SharedWebSocket;
import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.errors.SmplError;
import com.smplkit.internal.Debug;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigItemOverride;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import com.smplkit.internal.generated.config.model.EnvironmentOverride;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for the Smpl Config service.
 *
 * <p>Provides runtime config access ({@link #get(String)},
 * {@link #subscribe(String)}, {@link #onChange}) and management operations
 * via {@link #management()}.</p>
 */
public final class ConfigClient {

    private static final Logger LOG = Logger.getLogger("smplkit.config");

    final ConfigsApi configsApi;
    private volatile boolean connected;
    private volatile String environment;
    private volatile com.smplkit.MetricsReporter metrics;
    private Map<String, Map<String, Object>> configCache = new HashMap<>();
    private final List<ListenerEntry> listeners = Collections.synchronizedList(new ArrayList<>());
    private final ConfigManagement management;
    private volatile SharedWebSocket wsManager;
    private final Consumer<Map<String, Object>> configChangedHandler;
    private final Consumer<Map<String, Object>> configDeletedHandler;
    private final Consumer<Map<String, Object>> configsChangedHandler;

    /**
     * Creates a new ConfigClient. Use {@link com.smplkit.SmplClient} to obtain an instance.
     */
    public ConfigClient(ConfigsApi configsApi, java.net.http.HttpClient httpClient, String apiKey) {
        this.configsApi = configsApi;
        this.management = new ConfigManagement(this);
        this.configChangedHandler = this::handleConfigChanged;
        this.configDeletedHandler = this::handleConfigDeleted;
        this.configsChangedHandler = this::handleConfigsChanged;
    }

    // -----------------------------------------------------------------------
    // Management accessor
    // -----------------------------------------------------------------------

    /**
     * Returns the management-plane API for config CRUD operations.
     *
     * @return the {@link ConfigManagement} instance
     */
    public ConfigManagement management() {
        return management;
    }

    // -----------------------------------------------------------------------
    // Environment
    // -----------------------------------------------------------------------

    /**
     * Sets the target environment for config resolution.
     *
     * @param environment the environment name (e.g. "production")
     */
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /** Sets the metrics reporter. Package-private. */
    public void setMetrics(com.smplkit.MetricsReporter metrics) {
        this.metrics = metrics;
    }

    /** Sets the shared WebSocket for real-time config updates. */
    public void setSharedWs(SharedWebSocket ws) {
        this.wsManager = ws;
    }

    // -----------------------------------------------------------------------
    // Internal: create / update (called by Config.save())
    // -----------------------------------------------------------------------

    /** Creates a new config on the server. Called by {@link Config#save()}. */
    Config _createConfig(Config config) {
        try {
            var attrs = new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(config.getName());
            if (config.getDescription() != null) {
                attrs.setDescription(config.getDescription());
            }
            if (config.getParent() != null) {
                attrs.setParent(config.getParent());
            }
            if (config.getItems() != null && !config.getItems().isEmpty()) {
                attrs.setItems(wrapValuesAsItems(config.getResolvedItems()));
            }
            if (config.getEnvironments() != null && !config.getEnvironments().isEmpty()) {
                attrs.setEnvironments(wrapEnvironments(config.getEnvironments()));
            }

            ConfigResource data = new ConfigResource()
                    .id(config.getId())
                    .type(ConfigResource.TypeEnum.CONFIG)
                    .attributes(attrs);
            ConfigResponse body = new ConfigResponse().data(data);

            ConfigResponse response = configsApi.createConfig(body);
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Updates an existing config on the server. Called by {@link Config#save()}. */
    Config _updateConfig(Config config) {
        try {
            var attrs = new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(config.getName());
            if (config.getDescription() != null) {
                attrs.setDescription(config.getDescription());
            }
            if (config.getParent() != null) {
                attrs.setParent(config.getParent());
            }
            if (config.getItems() != null) {
                attrs.setItems(wrapValuesAsItems(config.getResolvedItems()));
            }
            if (config.getEnvironments() != null) {
                attrs.setEnvironments(wrapEnvironments(config.getEnvironments()));
            }

            ConfigResource data = new ConfigResource()
                    .id(config.getId())
                    .type(ConfigResource.TypeEnum.CONFIG)
                    .attributes(attrs);
            ConfigResponse body = new ConfigResponse().data(data);

            ConfigResponse response = configsApi.updateConfig(config.getId(), body);
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Runtime: get / subscribe
    // -----------------------------------------------------------------------

    /**
     * Returns resolved config values for the given id as a flat map.
     *
     * @param id the config id
     * @return resolved values map, or an empty map if not found
     */
    public Map<String, Object> get(String id) {
        _connectInternal();
        if (metrics != null) {
            metrics.record("config.resolutions", "resolutions", Map.of("config", id));
        }
        return new HashMap<>(configCache.getOrDefault(id, Map.of()));
    }

    /**
     * Returns resolved config values mapped to a model type.
     *
     * <p>Dot-notation keys (e.g. "database.host") are expanded into nested
     * objects before mapping to the model type.</p>
     *
     * @param id    the config id
     * @param model the target model class
     * @param <T>   the model type
     * @return an instance of the model type
     */
    public <T> T get(String id, Class<T> model) {
        _connectInternal();
        if (metrics != null) {
            metrics.record("config.resolutions", "resolutions", Map.of("config", id));
        }
        Map<String, Object> values = new HashMap<>(configCache.getOrDefault(id, Map.of()));
        Map<String, Object> nested = unflatten(values);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.convertValue(nested, model);
    }

    /**
     * Returns a {@link LiveConfig} for the given id that always reflects the latest values.
     *
     * @param id the config id
     * @return a LiveConfig returning {@code Map<String, Object>} values
     */
    @SuppressWarnings("unchecked")
    public LiveConfig<Map<String, Object>> subscribe(String id) {
        _connectInternal();
        return new LiveConfig<>(this, id, null);
    }

    /**
     * Returns a {@link LiveConfig} for the given id that always reflects the latest values,
     * mapped to the given model type.
     *
     * @param id    the config id
     * @param model the target model class
     * @param <T>   the model type
     * @return a LiveConfig returning instances of the model type
     */
    public <T> LiveConfig<T> subscribe(String id, Class<T> model) {
        _connectInternal();
        return new LiveConfig<>(this, id, model);
    }

    // -----------------------------------------------------------------------
    // Runtime: refresh / change listeners
    // -----------------------------------------------------------------------

    /**
     * Refreshes all config values from the server and fires change listeners
     * for any values that changed.
     */
    public void refresh() {
        String env = this.environment;
        if (env == null) return;

        Map<String, Map<String, Object>> newCache = buildCache(env);
        Map<String, Map<String, Object>> oldCache = configCache;
        configCache = newCache;
        diffAndFire(oldCache, newCache, "manual");
    }

    /**
     * Registers a global change listener that fires when any config value changes.
     *
     * @param listener called with a {@link ConfigChangeEvent} on each change
     */
    public void onChange(Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(null, null, listener));
    }

    /**
     * Registers a config-scoped change listener.
     *
     * @param configId only fire for changes to this config
     * @param listener called with a {@link ConfigChangeEvent} on each matching change
     */
    public void onChange(String configId, Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(configId, null, listener));
    }

    /**
     * Registers an item-scoped change listener.
     *
     * @param configId only fire for changes to this config
     * @param itemKey  only fire for changes to this item
     * @param listener called with a {@link ConfigChangeEvent} on each matching change
     */
    public void onChange(String configId, String itemKey, Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(configId, itemKey, listener));
    }

    /**
     * Initializes the config cache on first use. Idempotent.
     */
    void _connectInternal() {
        if (connected) return;
        String env = this.environment;
        if (env == null) return;

        Debug.log("websocket", "config runtime initializing");
        configCache = buildCache(env);

        SharedWebSocket ws = this.wsManager;
        if (ws != null) {
            Debug.log("registration", "registering config_changed, config_deleted, and configs_changed handlers");
            ws.on("config_changed", configChangedHandler);
            ws.on("config_deleted", configDeletedHandler);
            ws.on("configs_changed", configsChangedHandler);
            ws.ensureConnected(java.time.Duration.ofSeconds(10));
        }

        connected = true;
        Debug.log("websocket", "config runtime connected");
    }

    private void handleConfigChanged(Map<String, Object> data) {
        if (!connected) return;
        String configKey = data.get("id") instanceof String s ? s : null;
        if (configKey == null) {
            Debug.log("websocket", "config_changed event missing id, skipping");
            return;
        }
        Debug.log("websocket", "config_changed event received, key=" + configKey);

        String env = this.environment;
        if (env == null) return;

        // Scoped fetch: GET /configs/{key}
        Config fetched;
        try {
            fetched = management.get(configKey);
        } catch (Exception e) {
            Debug.log("websocket", "config_changed scoped fetch failed for key=" + configKey + ": " + e);
            return;
        }

        // Rebuild resolution just for this config
        Map<String, Map<String, Object>> oldCache = configCache;
        Map<String, Map<String, Object>> newCache = new HashMap<>(configCache);

        List<Resolver.ChainEntry> chain = new ArrayList<>();
        chain.add(toChainEntry(fetched));

        // Walk parent chain using current cache configs
        Config current = fetched;
        List<Config> allConfigs = null; // lazy: only load if parent chain needed
        while (current.getParent() != null) {
            if (allConfigs == null) {
                allConfigs = management.list();
            }
            String parentId = current.getParent();
            Config parent = null;
            for (Config c : allConfigs) {
                if (parentId.equals(c.getId())) {
                    parent = c;
                    break;
                }
            }
            if (parent == null) break;
            chain.add(toChainEntry(parent));
            current = parent;
        }

        newCache.put(configKey, Resolver.resolve(chain, env));
        configCache = newCache;

        diffAndFire(oldCache, newCache, "websocket");
    }

    private void handleConfigDeleted(Map<String, Object> data) {
        if (!connected) return;
        String configKey = data.get("id") instanceof String s ? s : null;
        if (configKey == null) {
            Debug.log("websocket", "config_deleted event missing id, skipping");
            return;
        }
        Debug.log("websocket", "config_deleted event received, key=" + configKey);

        // Remove from cache — no HTTP fetch
        Map<String, Map<String, Object>> oldCache = configCache;
        Map<String, Map<String, Object>> newCache = new HashMap<>(configCache);
        Map<String, Object> removed = newCache.remove(configKey);
        configCache = newCache;

        if (removed != null) {
            // Fire listener with deleted=true for each item that existed
            for (String itemKey : removed.keySet()) {
                ConfigChangeEvent event = new ConfigChangeEvent(configKey, itemKey,
                        removed.get(itemKey), null, "websocket", true);
                for (ListenerEntry entry : listeners) {
                    if (entry.configId != null && !entry.configId.equals(configKey)) continue;
                    if (entry.itemKey != null && !entry.itemKey.equals(itemKey)) continue;
                    try {
                        entry.listener.accept(event);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING,
                                "Exception in onChange listener for deleted config " + configKey, e);
                    }
                }
            }
            // Also fire global listener for the delete, even if no items
            if (removed.isEmpty()) {
                ConfigChangeEvent event = new ConfigChangeEvent(configKey, null,
                        null, null, "websocket", true);
                for (ListenerEntry entry : listeners) {
                    if (entry.configId != null && !entry.configId.equals(configKey)) continue;
                    if (entry.itemKey != null) continue;
                    try {
                        entry.listener.accept(event);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING,
                                "Exception in onChange listener for deleted config " + configKey, e);
                    }
                }
            }
        }
    }

    private void handleConfigsChanged(Map<String, Object> data) {
        if (!connected) return;
        Debug.log("websocket", "configs_changed event received");
        refresh();
    }

    // -----------------------------------------------------------------------
    // Internal: cache building
    // -----------------------------------------------------------------------

    private Map<String, Map<String, Object>> buildCache(String env) {
        List<Config> allConfigs = management.list();
        Map<String, Config> configById = new HashMap<>();
        for (Config cfg : allConfigs) {
            if (cfg.getId() != null) {
                configById.put(cfg.getId(), cfg);
            }
        }

        Map<String, Map<String, Object>> newCache = new HashMap<>();
        for (Config cfg : allConfigs) {
            List<Resolver.ChainEntry> chain = new ArrayList<>();
            chain.add(toChainEntry(cfg));
            Config current = cfg;
            while (current.getParent() != null && configById.containsKey(current.getParent())) {
                Config parent = configById.get(current.getParent());
                chain.add(toChainEntry(parent));
                current = parent;
            }
            newCache.put(cfg.getId(), Resolver.resolve(chain, env));
        }
        return newCache;
    }

    // -----------------------------------------------------------------------
    // Internal: diff and fire
    // -----------------------------------------------------------------------

    /** Fires change listeners for any values that differ between old and new snapshots. */
    void diffAndFire(
            Map<String, Map<String, Object>> oldCache,
            Map<String, Map<String, Object>> newCache,
            String source
    ) {
        Set<String> allConfigIds = new HashSet<>();
        allConfigIds.addAll(oldCache.keySet());
        allConfigIds.addAll(newCache.keySet());

        for (String cfgId : allConfigIds) {
            Map<String, Object> oldItems = oldCache.getOrDefault(cfgId, Map.of());
            Map<String, Object> newItems = newCache.getOrDefault(cfgId, Map.of());

            Set<String> allItemKeys = new HashSet<>();
            allItemKeys.addAll(oldItems.keySet());
            allItemKeys.addAll(newItems.keySet());

            for (String itemKey : allItemKeys) {
                Object oldVal = oldItems.get(itemKey);
                Object newVal = newItems.get(itemKey);
                if (Objects.equals(oldVal, newVal)) continue;

                if (metrics != null) {
                    metrics.record("config.changes", "changes", Map.of("config", cfgId));
                }
                ConfigChangeEvent event = new ConfigChangeEvent(cfgId, itemKey, oldVal, newVal, source);
                for (ListenerEntry entry : listeners) {
                    if (entry.configId != null && !entry.configId.equals(cfgId)) continue;
                    if (entry.itemKey != null && !entry.itemKey.equals(itemKey)) continue;
                    try {
                        entry.listener.accept(event);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING,
                                "Exception in onChange listener for " + cfgId + "/" + itemKey, e);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Package-private: cache access (for LiveConfig)
    // -----------------------------------------------------------------------

    /** Returns resolved values for a config id. */
    Map<String, Object> _getResolvedCache(String id) {
        return configCache.getOrDefault(id, Map.of());
    }

    /** Package-private: check if connected (for testing). */
    boolean isConnected() {
        return connected;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static Resolver.ChainEntry toChainEntry(Config config) {
        // Resolver needs resolved items (plain values) and environments
        Map<String, Object> resolvedItems = config.getResolvedItems();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> envMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : config.getEnvironments().entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> val = (Map<String, Object>) entry.getValue();
                envMap.put(entry.getKey(), val);
            }
        }
        return new Resolver.ChainEntry(
                config.getId() != null ? config.getId() : "",
                resolvedItems,
                envMap);
    }

    /** Converts a server resource into the SDK's Config model. */
    Config parseResource(ConfigResource resource) {
        String id = resource.getId();
        var attrs = resource.getAttributes();

        String name = attrs.getName();
        String description = attrs.getDescription();
        String parent = attrs.getParent();

        // Store the FULL typed shape: {key: {value, type, description}}
        Map<String, Object> items = new HashMap<>();
        Map<String, ConfigItemDefinition> rawItems = attrs.getItems();
        if (rawItems != null) {
            for (Map.Entry<String, ConfigItemDefinition> entry : rawItems.entrySet()) {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("value", entry.getValue().getValue());
                if (entry.getValue().getType() != null) {
                    itemData.put("type", entry.getValue().getType().getValue());
                }
                if (entry.getValue().getDescription() != null) {
                    itemData.put("description", entry.getValue().getDescription());
                }
                items.put(entry.getKey(), itemData);
            }
        }

        // Extract environments
        Map<String, Object> environments = new HashMap<>();
        Map<String, EnvironmentOverride> rawEnvs = attrs.getEnvironments();
        if (rawEnvs != null) {
            for (Map.Entry<String, EnvironmentOverride> envEntry : rawEnvs.entrySet()) {
                EnvironmentOverride override = envEntry.getValue();
                Map<String, Object> envData = new HashMap<>();
                Map<String, ConfigItemOverride> envValues = override.getValues();
                if (envValues != null) {
                    Map<String, Object> extractedValues = new HashMap<>();
                    for (Map.Entry<String, ConfigItemOverride> valEntry : envValues.entrySet()) {
                        extractedValues.put(valEntry.getKey(), valEntry.getValue().getValue());
                    }
                    envData.put("values", extractedValues);
                }
                environments.put(envEntry.getKey(), envData);
            }
        }

        Instant createdAt = attrs.getCreatedAt() != null ? attrs.getCreatedAt().toInstant() : null;
        Instant updatedAt = attrs.getUpdatedAt() != null ? attrs.getUpdatedAt().toInstant() : null;

        Config config = new Config(this, id != null ? id : "", name != null ? name : "");
        config.setDescription(description);
        config.setParent(parent);
        config.setItems(items);
        config.setEnvironments(environments);
        config.setCreatedAt(createdAt);
        config.setUpdatedAt(updatedAt);
        return config;
    }

    /** Wraps plain values as typed items for the server. */
    static Map<String, ConfigItemDefinition> wrapValuesAsItems(Map<String, Object> values) {
        Map<String, ConfigItemDefinition> items = new HashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ConfigItemDefinition def = new ConfigItemDefinition();
            def.setValue(entry.getValue());
            def.setType(inferType(entry.getValue()));
            items.put(entry.getKey(), def);
        }
        return items;
    }

    /** Wraps environments for the server. */
    @SuppressWarnings("unchecked")
    static Map<String, EnvironmentOverride> wrapEnvironments(Map<String, Object> environments) {
        Map<String, EnvironmentOverride> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : environments.entrySet()) {
            EnvironmentOverride override = new EnvironmentOverride();
            if (entry.getValue() instanceof Map) {
                Map<String, Object> envData = (Map<String, Object>) entry.getValue();
                Object rawValues = envData.get("values");
                if (rawValues instanceof Map) {
                    Map<String, Object> valuesMap = (Map<String, Object>) rawValues;
                    Map<String, ConfigItemOverride> wrappedValues = new HashMap<>();
                    for (Map.Entry<String, Object> valEntry : valuesMap.entrySet()) {
                        ConfigItemOverride itemOverride = new ConfigItemOverride();
                        itemOverride.setValue(valEntry.getValue());
                        wrappedValues.put(valEntry.getKey(), itemOverride);
                    }
                    override.setValues(wrappedValues);
                }
            }
            result.put(entry.getKey(), override);
        }
        return result;
    }

    /** Returns the type enum for a value. */
    static ConfigItemDefinition.TypeEnum inferType(Object value) {
        if (value instanceof String) return ConfigItemDefinition.TypeEnum.STRING;
        if (value instanceof Number) return ConfigItemDefinition.TypeEnum.NUMBER;
        if (value instanceof Boolean) return ConfigItemDefinition.TypeEnum.BOOLEAN;
        return ConfigItemDefinition.TypeEnum.JSON;
    }

    /**
     * Converts dot-notation keys to nested maps.
     *
     * <p>Example: {@code {"database.host": "localhost", "database.port": 5432}}
     * becomes {@code {"database": {"host": "localhost", "port": 5432}}}.</p>
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> unflatten(Map<String, Object> flat) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            Map<String, Object> current = result;
            for (int i = 0; i < parts.length - 1; i++) {
                current = (Map<String, Object>) current.computeIfAbsent(
                        parts[i], k -> new HashMap<>());
            }
            current.put(parts[parts.length - 1], entry.getValue());
        }
        return result;
    }

    static SmplError mapException(ApiException e) {
        if (e.getCode() == 0) {
            return ApiExceptionHandler.mapApiException(e);
        }
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }

    /** Simulates a config_changed WebSocket event (for testing). */
    void simulateConfigChanged(Map<String, Object> data) {
        handleConfigChanged(data);
    }

    /** Simulates a config_deleted WebSocket event (for testing). */
    void simulateConfigDeleted(Map<String, Object> data) {
        handleConfigDeleted(data);
    }

    /** Simulates a configs_changed WebSocket event (for testing). */
    void simulateConfigsChanged(Map<String, Object> data) {
        handleConfigsChanged(data);
    }

    private record ListenerEntry(String configId, String itemKey,
                                 Consumer<ConfigChangeEvent> listener) {}
}
