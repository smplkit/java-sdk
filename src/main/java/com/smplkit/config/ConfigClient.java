package com.smplkit.config;

import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigInput;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigItemOverride;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigOutput;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import com.smplkit.internal.generated.config.model.EnvironmentOverride;
import com.smplkit.internal.generated.config.model.ResourceConfig;
import com.smplkit.internal.generated.config.model.ResponseConfig;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client for the Smpl Config service.
 *
 * <p>Provides CRUD operations on configuration resources and runtime connection.
 * Obtained via {@link com.smplkit.SmplClient#config()}.</p>
 *
 * <p>All methods communicate with the server synchronously and raise
 * structured exceptions on failure.</p>
 */
public final class ConfigClient {

    private static final String WS_BASE_URL = "https://config.smplkit.com";

    private final ConfigsApi configsApi;
    private final HttpClient httpClient;
    private final String apiKey;

    /**
     * Creates a new ConfigClient. Use {@link com.smplkit.SmplClient} to obtain an instance.
     *
     * @param configsApi the generated API client
     * @param httpClient the HTTP client (used for WebSocket in ConfigRuntime)
     * @param apiKey     the API key (used for WebSocket auth)
     */
    public ConfigClient(ConfigsApi configsApi, HttpClient httpClient, String apiKey) {
        this.configsApi = configsApi;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
    }

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
            ConfigInput attrs = new ConfigInput();
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

            ConfigInput attrs = new ConfigInput();
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
        Map<String, Object> merged = ConfigRuntime.deepMerge(existingValues, newValues);

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

    /**
     * Connects to a config for runtime use, returning a fully-resolved {@link ConfigRuntime}.
     *
     * <p>Fetches the full parent chain, resolves values for the given environment,
     * and starts a WebSocket for real-time updates.</p>
     *
     * @param config      the config to connect to
     * @param environment the target environment name
     * @return a ready-to-use {@link ConfigRuntime}
     */
    public ConfigRuntime connect(Config config, String environment) {
        // Build the chain: child-first, walk parent links via get()
        List<Config> configChain = new ArrayList<>();
        Config current = config;
        while (current != null) {
            configChain.add(current);
            if (current.parent() != null) {
                current = get(current.parent());
            } else {
                break;
            }
        }
        int fetchCount = configChain.size();

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        for (Config c : configChain) {
            chain.add(new ConfigRuntime.ChainEntry(c.id(), c.items(), c.environments()));
        }

        // fetchChainFn for refresh/reconnect — re-fetches the same chain
        List<String> chainIds = new ArrayList<>();
        for (Config c : configChain) {
            chainIds.add(c.id());
        }

        return ConfigRuntime.create(
                chain,
                config.id(),
                config.key(),
                environment,
                httpClient,
                apiKey,
                WS_BASE_URL,
                () -> fetchChain(chainIds),
                fetchCount
        );
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private List<ConfigRuntime.ChainEntry> fetchChain(List<String> ids) {
        List<ConfigRuntime.ChainEntry> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            Config c = get(id);
            entries.add(new ConfigRuntime.ChainEntry(c.id(), c.items(), c.environments()));
        }
        return entries;
    }

    /**
     * Parses a generated ConfigResource into the SDK's Config record.
     *
     * <p>Extracts raw values from typed item definitions and environment overrides.</p>
     */
    private Config parseResource(ConfigResource resource) {
        String id = resource.getId();
        ConfigOutput attrs = resource.getAttributes();

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
        int code = e.getCode();
        String body = e.getResponseBody();
        String msg = e.getMessage() != null ? e.getMessage() : "HTTP " + code;
        return switch (code) {
            case 404 -> new SmplNotFoundException(msg, body);
            case 409 -> new SmplConflictException(msg, body);
            case 422 -> new SmplValidationException(msg, body);
            default -> new SmplException(msg, code, body);
        };
    }
}
