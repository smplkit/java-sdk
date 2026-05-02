package com.smplkit.config;

import java.util.Map;

/**
 * Per-environment view of a config resource.
 *
 * <p>Immutable record; mirrors Python's {@code ConfigEnvironment}.
 * {@code values} is the resolved {@code key -> value} map for the environment
 * (string / number / boolean / json values), and {@code valuesRaw} is the
 * full {@code key -> {value, type, description}} typed map for advanced use.
 * Both maps are immutable.</p>
 */
public record ConfigEnvironment(Map<String, Object> values, Map<String, Object> valuesRaw) {

    public ConfigEnvironment {
        values = values != null ? Map.copyOf(values) : Map.of();
        valuesRaw = valuesRaw != null ? deepCopyImmutable(valuesRaw) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyImmutable(Map<String, Object> raw) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) {
                result.put(e.getKey(), Map.copyOf((Map<String, Object>) v));
            } else {
                result.put(e.getKey(), v);
            }
        }
        return Map.copyOf(result);
    }
}
