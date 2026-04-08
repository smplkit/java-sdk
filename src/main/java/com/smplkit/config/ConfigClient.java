package com.smplkit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smplkit.Helpers;
import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigItemOverride;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import com.smplkit.internal.generated.config.model.EnvironmentOverride;
import com.smplkit.internal.generated.config.model.ResourceConfig;
import com.smplkit.internal.generated.config.model.ResponseConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for the Smpl Config service.
 *
 * <p>Provides management CRUD ({@link #new_(String)}, {@link #get(String)},
 * {@link #list()}, {@link #delete(String)}) and runtime resolution
 * ({@link #resolve(String)}, {@link #subscribe(String)}, {@link #onChange}).</p>
 *
 * <p>Management methods always use HTTP and do NOT trigger lazy init.
 * Runtime methods ({@code resolve}, {@code subscribe}) trigger lazy init
 * on first call, which bulk-fetches all configs and resolves inheritance.</p>
 */
public final class ConfigClient {

    private static final Logger LOG = Logger.getLogger("smplkit.config");

    private final ConfigsApi configsApi;
    private volatile boolean connected;
    private volatile String environment;
    private Map<String, Map<String, Object>> configCache = new HashMap<>();
    private final List<ListenerEntry> listeners = Collections.synchronizedList(new ArrayList<>());

    /**
     * Creates a new ConfigClient. Use {@link com.smplkit.SmplClient} to obtain an instance.
     *
     * @param configsApi the generated API client
     * @param httpClient the HTTP client (kept for API compat)
     * @param apiKey     the API key (kept for API compat)
     */
    public ConfigClient(ConfigsApi configsApi, java.net.http.HttpClient httpClient, String apiKey) {
        this.configsApi = configsApi;
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

    // -----------------------------------------------------------------------
    // Management: factory methods
    // -----------------------------------------------------------------------

    /**
     * Returns an unsaved {@link Config} with {@code id=null}.
     * Call {@link Config#save()} to persist.
     *
     * @param key human-readable key
     * @return a new unsaved Config
     */
    public Config new_(String key) {
        return new_(key, null, null, null);
    }

    /**
     * Returns an unsaved {@link Config} with {@code id=null}.
     * Call {@link Config#save()} to persist.
     *
     * @param key         human-readable key
     * @param name        display name (auto-generated from key if null)
     * @param description optional description
     * @param parent      parent config UUID, or null
     * @return a new unsaved Config
     */
    public Config new_(String key, String name, String description, String parent) {
        Config config = new Config(this, key, name != null ? name : Helpers.keyToDisplayName(key));
        config.setDescription(description);
        config.setParent(parent);
        return config;
    }

    // -----------------------------------------------------------------------
    // Management: CRUD
    // -----------------------------------------------------------------------

    /**
     * Fetches a config by its human-readable key. Always HTTP.
     *
     * @param key the config key
     * @return the matching Config
     * @throws SmplNotFoundException if no matching config exists
     */
    public Config get(String key) {
        try {
            ConfigListResponse response = configsApi.listConfigs(key, null);
            List<ConfigResource> data = response.getData();
            if (data == null || data.isEmpty()) {
                throw new SmplNotFoundException("Config with key '" + key + "' not found", null);
            }
            return parseResource(data.get(0));
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Lists all configs for the account. Always HTTP.
     *
     * @return an unmodifiable list of configs
     */
    public List<Config> list() {
        try {
            ConfigListResponse response = configsApi.listConfigs(null, null);
            List<ConfigResource> data = response.getData();
            if (data == null) {
                return Collections.emptyList();
            }
            List<Config> result = new ArrayList<>(data.size());
            for (ConfigResource resource : data) {
                result.add(parseResource(resource));
            }
            return Collections.unmodifiableList(result);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Deletes a config by key. Resolves key to UUID internally.
     *
     * @param key the config key
     * @throws SmplNotFoundException if the config does not exist
     */
    public void delete(String key) {
        Config config = get(key);
        try {
            configsApi.deleteConfig(UUID.fromString(config.getId()));
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Internal: create / update (called by Config.save())
    // -----------------------------------------------------------------------

    /**
     * Internal: POST a new config. Called by {@link Config#save()} when id is null.
     */
    Config _createConfig(Config config) {
        try {
            var attrs = new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(config.getName());
            if (config.getKey() != null) {
                attrs.setKey(config.getKey());
            }
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

            ResourceConfig data = new ResourceConfig()
                    .type("config")
                    .attributes(attrs);
            ResponseConfig body = new ResponseConfig().data(data);

            ConfigResponse response = configsApi.createConfig(body);
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Internal: PUT a full config update. Called by {@link Config#save()} when id is set.
     */
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

            ResourceConfig data = new ResourceConfig()
                    .id(config.getId())
                    .type("config")
                    .attributes(attrs);
            ResponseConfig body = new ResponseConfig().data(data);

            ConfigResponse response = configsApi.updateConfig(
                    UUID.fromString(config.getId()), body);
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Runtime: resolve / subscribe
    // -----------------------------------------------------------------------

    /**
     * Returns resolved config values for the given key as a flat map.
     *
     * <p>Triggers lazy init via {@link #_connectInternal()} on first call.</p>
     *
     * @param key the config key
     * @return resolved values map, or an empty map if not found
     */
    public Map<String, Object> resolve(String key) {
        _connectInternal();
        return new HashMap<>(configCache.getOrDefault(key, Map.of()));
    }

    /**
     * Returns resolved config values mapped to a model type.
     *
     * <p>Dot-notation keys are unflattened into a nested map, then converted
     * to the model type via Jackson's {@code ObjectMapper.convertValue}.</p>
     *
     * @param key   the config key
     * @param model the target model class
     * @param <T>   the model type
     * @return an instance of the model type
     */
    public <T> T resolve(String key, Class<T> model) {
        _connectInternal();
        Map<String, Object> values = new HashMap<>(configCache.getOrDefault(key, Map.of()));
        Map<String, Object> nested = unflatten(values);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.convertValue(nested, model);
    }

    /**
     * Returns a {@link LiveConfig} proxy for the given key (Map mode).
     *
     * <p>The proxy delegates to the latest cache state on every access,
     * so values update automatically after {@link #refresh()}.</p>
     *
     * @param key the config key
     * @return a LiveConfig proxy
     */
    @SuppressWarnings("unchecked")
    public LiveConfig<Map<String, Object>> subscribe(String key) {
        _connectInternal();
        return new LiveConfig<>(this, key, null);
    }

    /**
     * Returns a {@link LiveConfig} proxy for the given key (model mode).
     *
     * @param key   the config key
     * @param model the target model class
     * @param <T>   the model type
     * @return a LiveConfig proxy
     */
    public <T> LiveConfig<T> subscribe(String key, Class<T> model) {
        _connectInternal();
        return new LiveConfig<>(this, key, model);
    }

    // -----------------------------------------------------------------------
    // Runtime: refresh / change listeners
    // -----------------------------------------------------------------------

    /**
     * Re-fetches all configs, re-resolves values for the current environment,
     * and fires change listeners for any values that differ from the previous cache.
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
     * @param configKey only fire for changes to this config
     * @param listener  called with a {@link ConfigChangeEvent} on each matching change
     */
    public void onChange(String configKey, Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(configKey, null, listener));
    }

    /**
     * Registers an item-scoped change listener.
     *
     * @param configKey only fire for changes to this config
     * @param itemKey   only fire for changes to this item
     * @param listener  called with a {@link ConfigChangeEvent} on each matching change
     */
    public void onChange(String configKey, String itemKey, Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(configKey, itemKey, listener));
    }

    // -----------------------------------------------------------------------
    // Lazy init
    // -----------------------------------------------------------------------

    /**
     * Lazy init: fetch all configs, resolve inheritance, cache resolved values.
     * Idempotent -- returns immediately if already connected.
     */
    void _connectInternal() {
        if (connected) return;
        String env = this.environment;
        if (env == null) return;

        configCache = buildCache(env);
        connected = true;
    }

    // -----------------------------------------------------------------------
    // Internal: cache building
    // -----------------------------------------------------------------------

    private Map<String, Map<String, Object>> buildCache(String env) {
        List<Config> allConfigs = list();
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
            newCache.put(cfg.getKey(), Resolver.resolve(chain, env));
        }
        return newCache;
    }

    // -----------------------------------------------------------------------
    // Internal: diff and fire
    // -----------------------------------------------------------------------

    /**
     * Compares old and new caches, fires change listeners for any differences.
     */
    void diffAndFire(
            Map<String, Map<String, Object>> oldCache,
            Map<String, Map<String, Object>> newCache,
            String source
    ) {
        Set<String> allConfigKeys = new HashSet<>();
        allConfigKeys.addAll(oldCache.keySet());
        allConfigKeys.addAll(newCache.keySet());

        for (String cfgKey : allConfigKeys) {
            Map<String, Object> oldItems = oldCache.getOrDefault(cfgKey, Map.of());
            Map<String, Object> newItems = newCache.getOrDefault(cfgKey, Map.of());

            Set<String> allItemKeys = new HashSet<>();
            allItemKeys.addAll(oldItems.keySet());
            allItemKeys.addAll(newItems.keySet());

            for (String itemKey : allItemKeys) {
                Object oldVal = oldItems.get(itemKey);
                Object newVal = newItems.get(itemKey);
                if (Objects.equals(oldVal, newVal)) continue;

                ConfigChangeEvent event = new ConfigChangeEvent(cfgKey, itemKey, oldVal, newVal, source);
                for (ListenerEntry entry : listeners) {
                    if (entry.configKey != null && !entry.configKey.equals(cfgKey)) continue;
                    if (entry.itemKey != null && !entry.itemKey.equals(itemKey)) continue;
                    try {
                        entry.listener.accept(event);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING,
                                "Exception in onChange listener for " + cfgKey + "/" + itemKey, e);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Package-private: cache access (for LiveConfig)
    // -----------------------------------------------------------------------

    /**
     * Returns resolved values for a key from the cache. Used by {@link LiveConfig}.
     */
    Map<String, Object> _getResolvedCache(String key) {
        return configCache.getOrDefault(key, Map.of());
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

    /**
     * Parses a generated ConfigResource into the SDK's Config.
     *
     * <p>Stores the FULL typed shape from the API in the items field.</p>
     */
    Config parseResource(ConfigResource resource) {
        String id = resource.getId();
        var attrs = resource.getAttributes();

        String key = attrs.getKey();
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

        Config config = new Config(this, key != null ? key : "", name != null ? name : "");
        config.setId(id != null ? id : "");
        config.setDescription(description);
        config.setParent(parent);
        config.setItems(items);
        config.setEnvironments(environments);
        config.setCreatedAt(createdAt);
        config.setUpdatedAt(updatedAt);
        return config;
    }

    /**
     * Wraps resolved values as typed items for the API.
     * Each value is wrapped as a ConfigItemDefinition with inferred type.
     */
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

    /**
     * Wraps environments for the API: converts raw value maps to EnvironmentOverride
     * with ConfigItemOverride wrappers.
     */
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

    /** Infers the type string for a value. */
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

    private static SmplException mapException(ApiException e) {
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }

    private record ListenerEntry(String configKey, String itemKey,
                                 Consumer<ConfigChangeEvent> listener) {}
}
