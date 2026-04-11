package com.smplkit.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * A live-updating config proxy.
 *
 * <p>Always returns the latest resolved values, so values update
 * automatically after {@link ConfigClient#refresh()}.</p>
 *
 * @param <T> the model type, or {@code Map} for untyped access
 */
public final class LiveConfig<T> {

    private final ConfigClient client;
    private final String id;
    private final Class<T> modelType; // null for Map mode

    LiveConfig(ConfigClient client, String id, Class<T> modelType) {
        this.client = client;
        this.id = id;
        this.modelType = modelType;
    }

    /**
     * Returns the latest resolved values as a plain {@code Map<String, Object>}.
     *
     * @return resolved values, or an empty map if the config id is not found
     */
    public Map<String, Object> getAsMap() {
        return new HashMap<>(client._getResolvedCache(id));
    }

    /**
     * Returns the latest resolved values mapped to the model type.
     *
     * @return the model instance
     * @throws IllegalStateException if no model type was specified
     */
    public T get() {
        if (modelType == null) {
            throw new IllegalStateException(
                    "No model type specified. Use getAsMap() or subscribe with a model class.");
        }
        Map<String, Object> values = client._getResolvedCache(id);
        Map<String, Object> nested = ConfigClient.unflatten(values);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.convertValue(nested, modelType);
    }

    /** Returns the config id this proxy is subscribed to. */
    public String getId() {
        return id;
    }

    /** Returns the model type, or null if returning raw maps. */
    public Class<T> getModelType() {
        return modelType;
    }
}
