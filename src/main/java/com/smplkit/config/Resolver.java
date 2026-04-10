package com.smplkit.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves configuration values through inheritance chains.
 */
final class Resolver {

    private Resolver() {}

    /**
     * Mutable chain entry holding values and environment overrides for one config.
     */
    static final class ChainEntry {
        final String id;
        Map<String, Object> values;
        Map<String, Map<String, Object>> environments;

        ChainEntry(String id, Map<String, Object> values, Map<String, Map<String, Object>> environments) {
            this.id = id;
            this.values = values != null ? new HashMap<>(values) : new HashMap<>();
            this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
        }
    }

    /**
     * Resolves the full configuration for an environment from an inheritance chain.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> resolve(List<ChainEntry> chain, String environment) {
        Map<String, Object> accumulated = new HashMap<>();
        // Walk root-first (chain is child-first, so iterate in reverse)
        for (int i = chain.size() - 1; i >= 0; i--) {
            ChainEntry entry = chain.get(i);
            Map<String, Object> base = entry.values != null ? entry.values : Map.of();
            Map<String, Object> envData = entry.environments != null
                    ? entry.environments.get(environment)
                    : null;
            Map<String, Object> envValues = Map.of();
            if (envData != null) {
                Object rawEnvValues = envData.get("values");
                if (rawEnvValues instanceof Map) {
                    envValues = (Map<String, Object>) rawEnvValues;
                }
            }
            Map<String, Object> configResolved = deepMerge(base, envValues);
            accumulated = deepMerge(accumulated, configResolved);
        }
        return accumulated;
    }

    /**
     * Merges two maps, with {@code override} taking precedence.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new HashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object existing = result.get(key);
            if (existing instanceof Map && value instanceof Map) {
                result.put(key, deepMerge((Map<String, Object>) existing, (Map<String, Object>) value));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }
}
