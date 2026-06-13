package com.smplkit.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep-merge resolution algorithm for config inheritance chains.
 *
 * <p>Implements config inheritance: a child config inherits every item from its
 * parent chain, with the nearest config (and environment override) winning.</p>
 */
final class Resolver {

    private Resolver() {}

    /**
     * Mutable chain entry holding items and environment overrides for one
     * config. Each entry corresponds to one config-data dict in the python
     * chain: its {@code items} are the typed {@code {key: {value, type, desc}}}
     * (or already-unwrapped {@code {key: raw}}) map, and its {@code environments}
     * are the flat {@code {env: {key: rawValue}}} overrides.
     */
    static final class ChainEntry {
        final String id;
        Map<String, Object> items;
        Map<String, Map<String, Object>> environments;

        ChainEntry(String id, Map<String, Object> items, Map<String, Map<String, Object>> environments) {
            this.id = id;
            this.items = items != null ? new HashMap<>(items) : new HashMap<>();
            this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
        }
    }

    /**
     * Recursively merge two dicts, with {@code override} taking precedence.
     *
     * @param base     The base dictionary (lower priority).
     * @param override The override dictionary (higher priority).
     * @return A new dict with values from both, where {@code override} wins on
     *     conflict. Nested dicts are merged recursively. Non-dict values
     *     (strings, numbers, booleans, arrays, null) are replaced wholesale.
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

    /**
     * Unwrap typed items {@code {key: {value, type, desc}}} to {@code {key: raw}}.
     *
     * <p>Also handles plain {@code {key: raw}} for backward compatibility.</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapItems(Map<String, Object> items) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> e : items.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m && m.containsKey("value")) {
                result.put(e.getKey(), ((Map<String, Object>) m).get("value"));
            } else {
                result.put(e.getKey(), v);
            }
        }
        return result;
    }

    /**
     * Resolve the full configuration for an environment given a config chain.
     *
     * <p>Walks the chain from root (last element) to child (first element),
     * accumulating values via deep merge so that child configs override
     * parent configs.</p>
     *
     * <p>For each config in the chain, the config's base {@code items} (or legacy
     * {@code values}) are first unwrapped from the typed shape, then merged with
     * environment-specific values (environment wins), then that result is
     * merged on top of the accumulated parent result (child wins over parent).</p>
     *
     * <p>Each {@code environments} entry IS the flat override map
     * {@code {key: rawValue}} — no {@code "values"} envelope, no per-override wrapper.</p>
     *
     * @param chain       Ordered list of config data entries from child (index 0) to
     *     root ancestor (last index). Each entry has {@code items} (or {@code values})
     *     and {@code environments} (dict).
     * @param environment The environment key to resolve for.
     * @return A flat dict of config item keys to their resolved JSON values.
     */
    static Map<String, Object> resolve(List<ChainEntry> chain, String environment) {
        Map<String, Object> accumulated = new HashMap<>();

        // Walk from root to child (reverse order)
        for (int i = chain.size() - 1; i >= 0; i--) {
            ChainEntry entry = chain.get(i);
            // Support both new "items" field and legacy "values" field
            Map<String, Object> rawItems = entry.items != null ? entry.items : Map.of();
            Map<String, Object> baseValues = unwrapItems(rawItems);

            // Each env entry IS the flat override map.
            Map<String, Object> envData = entry.environments != null
                    ? entry.environments.getOrDefault(environment, Map.of())
                    : Map.of();
            Map<String, Object> envValues = envData != null ? new HashMap<>(envData) : new HashMap<>();

            // Merge environment overrides on top of base values
            Map<String, Object> configResolved = deepMerge(baseValues, envValues);

            // Merge this config's resolved values on top of accumulated parent values
            accumulated = deepMerge(accumulated, configResolved);
        }

        return accumulated;
    }
}
