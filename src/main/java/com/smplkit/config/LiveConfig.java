package com.smplkit.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * A live-updating config proxy.
 *
 * <p>Delegates to the latest cache state on every access, so values
 * update automatically after {@link ConfigClient#refresh()}.</p>
 *
 * @param <T> the model type, or {@code Map} for untyped access
 */
public final class LiveConfig<T> {

    private final ConfigClient client;
    private final String key;
    private final Class<T> modelType; // null for Map mode

    LiveConfig(ConfigClient client, String key, Class<T> modelType) {
        this.client = client;
        this.key = key;
        this.modelType = modelType;
    }

    /**
     * Returns the latest resolved values as a plain {@code Map<String, Object>}.
     *
     * @return resolved values, or an empty map if the config key is not cached
     */
    public Map<String, Object> getAsMap() {
        return new HashMap<>(client._getResolvedCache(key));
    }

    /**
     * Returns the latest resolved values mapped to the model type.
     *
     * <p>Dot-notation keys are unflattened into a nested map structure,
     * then converted to the model type using Jackson's
     * {@code ObjectMapper.convertValue}.</p>
     *
     * @return the model instance
     * @throws IllegalStateException if no model type was specified
     */
    public T get() {
        if (modelType == null) {
            throw new IllegalStateException(
                    "No model type specified. Use getAsMap() or subscribe with a model class.");
        }
        Map<String, Object> values = client._getResolvedCache(key);
        Map<String, Object> nested = ConfigClient.unflatten(values);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.convertValue(nested, modelType);
    }

    /** Returns the config key this proxy is subscribed to. */
    public String getKey() {
        return key;
    }

    /** Returns the model type, or null if in Map mode. */
    public Class<T> getModelType() {
        return modelType;
    }
}
