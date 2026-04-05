package com.smplkit.config;

import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotConnectedException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
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
 * <p>Provides CRUD operations on configuration resources and prescriptive
 * typed access after {@link com.smplkit.SmplClient#connect()}.
 * Obtained via {@link com.smplkit.SmplClient#config()}.</p>
 *
 * <p>All methods communicate with the server synchronously and raise
 * structured exceptions on failure.</p>
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
     * @param httpClient the HTTP client (unused after ConfigRuntime removal, kept for API compat)
     * @param apiKey     the API key (unused after ConfigRuntime removal, kept for API compat)
     */
    public ConfigClient(ConfigsApi configsApi, java.net.http.HttpClient httpClient, String apiKey) {
        this.configsApi = configsApi;
    }

    // -----------------------------------------------------------------------
    // Management-plane CRUD
    // -----------------------------------------------------------------------

    /**
     * Fetches a single config by UUID.
     *
     * @param id the config UUID
     * @return the matching config
     * @throws SmplNotFoundException if no matching config exists
     */
    public Config get(String id) {
        try {
            ConfigResponse response = configsApi.getConfig(UUID.fromString(id));
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Fetches a single config by its human-readable key.
     *
     * @param key the config key
     * @return the matching config
     * @throws SmplNotFoundException if no matching config exists
     */
    public Config getByKey(String key) {
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
     * Creates a new config.
     *
     * @param params the creation parameters
     * @return the created config
     * @throws SmplValidationException if the server rejects the request
     */
    public Config create(CreateConfigParams params) {
        try {
            var attrs = new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(params.name());
            if (params.key() != null) {
                attrs.setKey(params.key());
            }
            if (params.description() != null) {
                attrs.setDescription(params.description());
            }
            if (params.parent() != null) {
                attrs.setParent(params.parent());
            }
            if (params.values() != null) {
                attrs.setItems(wrapValuesAsItems(params.values()));
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
     * Lists all configs for the account.
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
     * Deletes a config by UUID.
     *
     * @param id the UUID of the config to delete
     * @throws SmplNotFoundException     if the config does not exist
     * @throws SmplConflictException     if the config has children
     */
    public void delete(String id) {
        try {
            configsApi.deleteConfig(UUID.fromString(id));
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Updates an existing config with the provided parameters.
     *
     * <p>Only fields set on {@code params} are changed; unset fields retain their current values.</p>
     *
     * @param config the current config (provides the UUID and default values)
     * @param params fields to update
     * @return the updated config
     */
    public Config update(Config config, UpdateConfigParams params) {
        try {
            String name = params.name() != null ? params.name() : config.name();
            String description = params.description() != null ? params.description() : config.description();
            Map<String, Object> items = params.values() != null ? params.values() : config.items();
            Map<String, Map<String, Object>> environments =
                    params.environments() != null ? params.environments() : config.environments();

            var attrs = new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(name);
            if (description != null) {
                attrs.setDescription(description);
            }
            // Always preserve parent so PUT doesn't clear it
            if (config.parent() != null) {
                attrs.setParent(config.parent());
            }
            if (items != null) {
                attrs.setItems(wrapValuesAsItems(items));
            }
            if (environments != null) {
                attrs.setEnvironments(wrapEnvironments(environments));
            }

            ResourceConfig data = new ResourceConfig()
                    .id(config.id())
                    .type("config")
                    .attributes(attrs);
            ResponseConfig body = new ResponseConfig().data(data);

            ConfigResponse response = configsApi.updateConfig(UUID.fromString(config.id()), body);
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Sets (deep-merges) values for a specific environment on an existing config.
     *
     * @param config      the config to update
     * @param newValues   the values to merge in
     * @param environment the target environment name (e.g. {@code "production"})
     * @return the updated config
     */
    public Config setValues(Config config, Map<String, Object> newValues, String environment) {
        Map<String, Map<String, Object>> envs = new HashMap<>(config.environments());
        Map<String, Object> existingEnv = envs.getOrDefault(environment, new HashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> existingValues = existingEnv.containsKey("values")
                ? new HashMap<>((Map<String, Object>) existingEnv.get("values"))
                : new HashMap<>();
        Map<String, Object> merged = Resolver.deepMerge(existingValues, newValues);

        Map<String, Object> envData = new HashMap<>(existingEnv);
        envData.put("values", merged);
        envs.put(environment, envData);

        UpdateConfigParams params = UpdateConfigParams.builder()
                .environments(envs)
                .build();
        return update(config, params);
    }

    /**
     * Sets a single key/value pair for a specific environment on an existing config.
     *
     * @param config      the config to update
     * @param key         the config key to set
     * @param value       the new value
     * @param environment the target environment name
     * @return the updated config
     */
    public Config setValue(Config config, String key, Object value, String environment) {
        return setValues(config, Map.of(key, value), environment);
    }

    // -----------------------------------------------------------------------
    // Prescriptive access — connect once, read everywhere
    // -----------------------------------------------------------------------

    /**
     * Internal connect called by SmplClient.connect(). Fetches all configs,
     * resolves values for the given environment, and caches them.
     *
     * @param environment the target environment
     */
    /** @hidden Internal — called by SmplClient.connect(). */
    public void connectInternal(String environment) {
        this.environment = environment;
        List<Config> allConfigs = list();
        Map<String, Config> configById = new HashMap<>();
        for (Config cfg : allConfigs) {
            configById.put(cfg.id(), cfg);
        }

        Map<String, Map<String, Object>> newCache = new HashMap<>();
        for (Config cfg : allConfigs) {
            List<Resolver.ChainEntry> chain = new ArrayList<>();
            chain.add(toChainEntry(cfg));
            Config current = cfg;
            while (current.parent() != null && configById.containsKey(current.parent())) {
                Config parent = configById.get(current.parent());
                chain.add(toChainEntry(parent));
                current = parent;
            }
            newCache.put(cfg.key(), Resolver.resolve(chain, environment));
        }

        configCache = newCache;
        connected = true;
    }

    /**
     * Prescriptive access: returns a resolved config value.
     *
     * @param configKey the config key
     * @param itemKey   the item key within the config
     * @return the resolved value, or null if not found
     * @throws SmplNotConnectedException if connect() has not been called
     */
    public Object getValue(String configKey, String itemKey) {
        if (!connected) {
            throw new SmplNotConnectedException();
        }
        Map<String, Object> resolved = configCache.get(configKey);
        if (resolved == null) return null;
        return resolved.get(itemKey);
    }

    /**
     * Prescriptive access: returns all resolved values for a config.
     *
     * @param configKey the config key
     * @return unmodifiable map of resolved values, or null if not found
     * @throws SmplNotConnectedException if connect() has not been called
     */
    public Map<String, Object> getValues(String configKey) {
        if (!connected) {
            throw new SmplNotConnectedException();
        }
        Map<String, Object> resolved = configCache.get(configKey);
        if (resolved == null) return null;
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Returns the value for {@code itemKey} as a {@link String}, or {@code defaultValue}
     * if absent or not a string.
     *
     * @throws SmplNotConnectedException if connect() has not been called
     */
    public String getString(String configKey, String itemKey, String defaultValue) {
        Object val = getValue(configKey, itemKey);
        return val instanceof String s ? s : defaultValue;
    }

    /**
     * Returns the value for {@code itemKey} as an {@code int}, or {@code defaultValue}
     * if absent or not a number.
     *
     * @throws SmplNotConnectedException if connect() has not been called
     */
    public int getInt(String configKey, String itemKey, int defaultValue) {
        Object val = getValue(configKey, itemKey);
        return val instanceof Number n ? n.intValue() : defaultValue;
    }

    /**
     * Returns the value for {@code itemKey} as a {@code boolean}, or {@code defaultValue}
     * if absent or not a boolean.
     *
     * @throws SmplNotConnectedException if connect() has not been called
     */
    public boolean getBool(String configKey, String itemKey, boolean defaultValue) {
        Object val = getValue(configKey, itemKey);
        return val instanceof Boolean b ? b : defaultValue;
    }

    /**
     * Re-fetches all configs, re-resolves values for the current environment,
     * and fires change listeners for any values that differ from the previous cache.
     *
     * @throws SmplNotConnectedException if connect() has not been called
     */
    public void refresh() {
        if (!connected) {
            throw new SmplNotConnectedException();
        }
        String env = this.environment;

        List<Config> allConfigs = list();
        Map<String, Config> configById = new HashMap<>();
        for (Config cfg : allConfigs) {
            configById.put(cfg.id(), cfg);
        }

        Map<String, Map<String, Object>> newCache = new HashMap<>();
        for (Config cfg : allConfigs) {
            List<Resolver.ChainEntry> chain = new ArrayList<>();
            chain.add(toChainEntry(cfg));
            Config current = cfg;
            while (current.parent() != null && configById.containsKey(current.parent())) {
                Config parent = configById.get(current.parent());
                chain.add(toChainEntry(parent));
                current = parent;
            }
            newCache.put(cfg.key(), Resolver.resolve(chain, env));
        }

        Map<String, Map<String, Object>> oldCache = configCache;
        configCache = newCache;
        diffAndFire(oldCache, newCache, "manual");
    }

    /**
     * Registers a listener that fires when any config value changes.
     *
     * @param listener called with a {@link ChangeEvent} on each change
     */
    public void onChange(Consumer<ChangeEvent> listener) {
        listeners.add(new ListenerEntry(null, null, listener));
    }

    /**
     * Registers a listener that fires when the specified config/item values change.
     *
     * @param listener  called with a {@link ChangeEvent} on each matching change
     * @param configKey only fire for changes to this config (null = all)
     * @param itemKey   only fire for changes to this item (null = all items in config)
     */
    public void onChange(Consumer<ChangeEvent> listener, String configKey, String itemKey) {
        listeners.add(new ListenerEntry(configKey, itemKey, listener));
    }

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

                ChangeEvent event = new ChangeEvent(cfgKey, itemKey, oldVal, newVal, source);
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

    /** Package-private: check if connected (for testing). */
    boolean isConnected() {
        return connected;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static Resolver.ChainEntry toChainEntry(Config config) {
        return new Resolver.ChainEntry(config.id(), config.items(), config.environments());
    }

    /**
     * Parses a generated ConfigResource into the SDK's Config record.
     *
     * <p>Extracts raw values from typed item definitions and environment overrides.</p>
     */
    private Config parseResource(ConfigResource resource) {
        String id = resource.getId();
        var attrs = resource.getAttributes();

        String key = attrs.getKey();
        String name = attrs.getName();
        String description = attrs.getDescription();
        String parent = attrs.getParent();

        // Extract raw values from typed items: {key: ConfigItemDefinition} -> {key: rawValue}
        Map<String, Object> items = new HashMap<>();
        Map<String, ConfigItemDefinition> rawItems = attrs.getItems();
        if (rawItems != null) {
            for (Map.Entry<String, ConfigItemDefinition> entry : rawItems.entrySet()) {
                items.put(entry.getKey(), entry.getValue().getValue());
            }
        }

        // Extract environments with raw values from overrides
        Map<String, Map<String, Object>> environments = new HashMap<>();
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

        return new Config(
                id != null ? id : "",
                key != null ? key : "",
                name != null ? name : "",
                description,
                parent,
                items,
                environments,
                createdAt,
                updatedAt
        );
    }

    /**
     * Wraps raw values as typed items for the API.
     * Each value is wrapped as a ConfigItemDefinition with inferred type.
     */
    private static Map<String, ConfigItemDefinition> wrapValuesAsItems(Map<String, Object> values) {
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
    private static Map<String, EnvironmentOverride> wrapEnvironments(
            Map<String, Map<String, Object>> environments) {
        Map<String, EnvironmentOverride> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : environments.entrySet()) {
            EnvironmentOverride override = new EnvironmentOverride();
            Map<String, Object> envData = entry.getValue();
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
            result.put(entry.getKey(), override);
        }
        return result;
    }

    /** Infers the type string for a value. */
    private static ConfigItemDefinition.TypeEnum inferType(Object value) {
        if (value instanceof String) return ConfigItemDefinition.TypeEnum.STRING;
        if (value instanceof Number) return ConfigItemDefinition.TypeEnum.NUMBER;
        if (value instanceof Boolean) return ConfigItemDefinition.TypeEnum.BOOLEAN;
        return ConfigItemDefinition.TypeEnum.JSON;
    }

    private static SmplException mapException(ApiException e) {
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }

    private record ListenerEntry(String configKey, String itemKey, Consumer<ChangeEvent> listener) {}
}
