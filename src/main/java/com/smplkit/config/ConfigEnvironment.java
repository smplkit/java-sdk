package com.smplkit.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-environment value overrides for a {@link Config}.
 *
 * <p>Read-only inspection container.  Mutation is performed via {@link Config}'s
 * setters with {@code environment="..."} (e.g. {@code cfg.setString("k", "v",
 * "production")}).</p>
 *
 * <p>Each environment entry is a flat map of item key to override value
 * ({@code {key: rawValue}}); per-override type and description are not carried —
 * they come from the base item, so a {@link ConfigItem}'s type and description
 * are ignored when an environment override is supplied.</p>
 */
public final class ConfigEnvironment {

    private final Map<String, Object> valuesRaw;

    /** Creates an empty environment override container. */
    public ConfigEnvironment() {
        this(null);
    }

    /**
     * Creates an environment override container from a {@code {key: rawValue}}
     * map. Tolerates legacy wrapped inputs {@code {key: {"value": v}}} for
     * constructor compatibility, but stores flat going forward.
     *
     * @param values the per-key override values, or {@code null} for an empty
     *     container
     */
    public ConfigEnvironment(Map<String, Object> values) {
        this.valuesRaw = new HashMap<>();
        if (values != null) {
            for (Map.Entry<String, Object> e : values.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map<?, ?> m
                        && m.containsKey("value")
                        && Set.of("value", "type", "description").containsAll(stringKeys(m))) {
                    this.valuesRaw.put(e.getKey(), m.get("value"));
                } else {
                    this.valuesRaw.put(e.getKey(), v);
                }
            }
        }
    }

    private static Set<String> stringKeys(Map<?, ?> m) {
        Set<String> out = new java.util.HashSet<>();
        for (Object k : m.keySet()) {
            out.add(String.valueOf(k));
        }
        return out;
    }

    /**
     * Return overrides as a plain {@code {key: rawValue}} map.
     *
     * @return a fresh map of item key to override value
     */
    public Map<String, Object> values() {
        return new HashMap<>(valuesRaw);
    }

    /**
     * Return the flat overrides {@code {key: rawValue}} (read-only shallow copy).
     *
     * <p>Aliased to {@link #values} now that overrides no longer carry
     * per-override {@code type} / {@code description} metadata.</p>
     *
     * @return a fresh map of item key to override value
     */
    public Map<String, Object> valuesRaw() {
        return new HashMap<>(valuesRaw);
    }

    /** Package-private mutable view used by {@link Config} setters / wire conversion. */
    Map<String, Object> rawMap() {
        return valuesRaw;
    }

    @Override
    public String toString() {
        return "ConfigEnvironment(values=" + values() + ")";
    }
}
