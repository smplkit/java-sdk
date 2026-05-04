package com.smplkit.flags;

import com.smplkit.Context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A feature flag with a typed value.
 *
 * <p>Use {@link #get()} to evaluate the flag. Use {@link #save()} to persist
 * changes to the server.</p>
 *
 * @param <T> the flag value type (Boolean, String, Number, Object)
 */
public final class Flag<T> {

    private FlagsClient client;
    private String id;
    private String name;
    private String type;
    private T defaultValue;
    private List<Map<String, Object>> values;
    private String description;
    private Map<String, Object> environments;
    private Instant createdAt;
    private Instant updatedAt;
    private final Class<T> valueType;

    Flag(FlagsClient client, String id, String name, String type, T defaultValue,
         List<Map<String, Object>> values, String description,
         Map<String, Object> environments, Instant createdAt, Instant updatedAt,
         Class<T> valueType) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.values = values != null ? new ArrayList<>(values) : null;
        this.description = description;
        this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.valueType = valueType;
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
    public T getDefault() { return defaultValue; }

    /**
     * @deprecated use {@link #values()} for the typed view returning
     * {@code List<FlagValue>}. The raw-Map accessor is kept for one cycle for
     * compatibility and will be removed in a follow-up.
     */
    @Deprecated
    public List<Map<String, Object>> getValues() { return values; }

    public String getDescription() { return description; }

    /**
     * @deprecated use {@link #environments()} for the typed view returning
     * {@code Map<String, FlagEnvironment>}. The raw-Map accessor is kept for
     * one cycle for compatibility and will be removed in a follow-up.
     */
    @Deprecated
    public Map<String, Object> getEnvironments() { return environments; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // --- Typed accessors (mirror Python rule 8) ---

    /**
     * Returns a typed, immutable per-environment view.
     *
     * <p>{@code flag.environments().get("production").enabled()},
     * {@code flag.environments().get("production").rules()}, etc. Mirrors
     * Python's {@code flag.environments["production"].enabled}.</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, FlagEnvironment> environments() {
        Map<String, FlagEnvironment> out = new HashMap<>();
        for (Map.Entry<String, Object> e : environments.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> envMap = (Map<String, Object>) e.getValue();
            boolean enabled = envMap.get("enabled") instanceof Boolean b ? b : true;
            Object def = envMap.get("default");
            Object rawRules = envMap.getOrDefault("rules", List.of());
            List<FlagRule> rules = new ArrayList<>();
            if (rawRules instanceof List) {
                for (Object r : (List<Object>) rawRules) {
                    if (!(r instanceof Map)) continue;
                    Map<String, Object> ruleMap = (Map<String, Object>) r;
                    Object logic = ruleMap.getOrDefault("logic", Map.of());
                    rules.add(new FlagRule(
                            logic instanceof Map ? (Map<String, Object>) logic : Map.of(),
                            ruleMap.get("value"),
                            (String) ruleMap.get("description")));
                }
            }
            out.put(e.getKey(), new FlagEnvironment(enabled, def, rules));
        }
        return Map.copyOf(out);
    }

    /**
     * Returns the constrained-values list as typed {@link FlagValue} records,
     * or {@code null} if the flag is unconstrained. Mirrors Python's
     * {@code flag.values}.
     */
    public List<FlagValue> values() {
        if (values == null) return null;
        List<FlagValue> out = new ArrayList<>(values.size());
        for (Map<String, Object> v : values) {
            out.add(new FlagValue((String) v.get("name"), v.get("value")));
        }
        return List.copyOf(out);
    }

    // --- Setters for mutable fields ---

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setDefault(T defaultValue) { this.defaultValue = defaultValue; }
    public void setValues(List<Map<String, Object>> values) {
        this.values = values != null ? new ArrayList<>(values) : null;
    }
    public void setEnvironments(Map<String, Object> environments) {
        this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
    }

    // --- Package-private setters (used by FlagsClient during _apply) ---

    void setId(String id) { this.id = id; }
    void setType(String type) { this.type = type; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    void setClient(FlagsClient client) { this.client = client; }
    FlagsClient getClient() { return client; }
    Class<T> getValueType() { return valueType; }

    // ------------------------------------------------------------------
    // Runtime: evaluation
    // ------------------------------------------------------------------

    /**
     * Returns the current value of this flag.
     */
    public T get() {
        return get((List<Context>) null);
    }

    /**
     * Returns the current value of this flag, evaluated against the given contexts.
     */
    @SuppressWarnings("unchecked")
    public T get(List<Context> contexts) {
        if (client == null) {
            return defaultValue;
        }
        Object raw = client._evaluateHandle(id, defaultValue, contexts);
        if (raw == null) {
            return defaultValue;
        }
        // Type coercion
        if (valueType == Boolean.class) {
            if (raw instanceof Boolean) return (T) raw;
            return defaultValue;
        }
        if (valueType == String.class) {
            if (raw instanceof String) return (T) raw;
            return defaultValue;
        }
        if (valueType == Number.class) {
            if (raw instanceof Boolean) return defaultValue;
            if (raw instanceof Number) return (T) raw;
            return defaultValue;
        }
        // Object.class or any other type — return raw
        return (T) raw;
    }

    // ------------------------------------------------------------------
    // Management: save (create or update)
    // ------------------------------------------------------------------

    /**
     * Persists this flag to the server.
     *
     * <p>After a successful save, this instance is refreshed with the
     * server response.</p>
     */
    public void save() {
        if (client == null) throw new IllegalStateException("Flag not bound to a client");
        if (createdAt == null) {
            Flag<T> created = client._createFlag(this);
            _apply(created);
        } else {
            Flag<T> updated = client._updateFlag(this);
            _apply(updated);
        }
    }

    /** Async variant of {@link #save()} (rule 12). */
    public java.util.concurrent.CompletableFuture<Void> saveAsync() {
        return saveAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Async variant of {@link #save()} with a custom executor. */
    public java.util.concurrent.CompletableFuture<Void> saveAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::save, executor);
    }

    /**
     * Deletes this flag from the server.
     *
     * <p>Mirrors Python's {@code flag.delete()}: delegates to
     * {@code mgmt.flags.delete(id)}. Bubbles {@link com.smplkit.errors.NotFoundError}
     * if the flag is not on the server.</p>
     *
     * @throws IllegalStateException if not bound to a client
     */
    public void delete() {
        if (client == null) throw new IllegalStateException("Flag not bound to a client");
        client.management().delete(id);
    }

    // ------------------------------------------------------------------
    // Management: local mutations
    // ------------------------------------------------------------------

    /**
     * Appends a rule to a specific environment. Call {@link #save()} to persist.
     *
     * <p>The built rule must include an "environment" key.</p>
     *
     * @return this for chaining
     */
    @SuppressWarnings("unchecked")
    public Flag<T> addRule(Map<String, Object> builtRule) {
        String envKey = (String) builtRule.get("environment");
        if (envKey == null) {
            throw new IllegalArgumentException(
                    "Built rule must include 'environment' key. "
                    + "Use Rule(...).environment(\"env_key\").when(...).serve(...).build()");
        }
        Map<String, Object> ruleCopy = new HashMap<>(builtRule);
        ruleCopy.remove("environment");

        Map<String, Object> envData = (Map<String, Object>) environments.computeIfAbsent(
                envKey, k -> new HashMap<>());
        List<Map<String, Object>> rules = (List<Map<String, Object>>) envData.computeIfAbsent(
                "rules", k -> new ArrayList<>());
        rules.add(ruleCopy);
        return this;
    }

    /**
     * Sets whether the flag is enabled in the given environment.
     * Call {@link #save()} to persist.
     */
    @SuppressWarnings("unchecked")
    public void setEnvironmentEnabled(String envKey, boolean enabled) {
        Map<String, Object> envData = (Map<String, Object>) environments.computeIfAbsent(
                envKey, k -> new HashMap<>());
        envData.put("enabled", enabled);
    }

    /**
     * Sets the environment-specific default value.
     * Call {@link #save()} to persist.
     */
    @SuppressWarnings("unchecked")
    public void setEnvironmentDefault(String envKey, Object defaultVal) {
        Map<String, Object> envData = (Map<String, Object>) environments.computeIfAbsent(
                envKey, k -> new HashMap<>());
        envData.put("default", defaultVal);
    }

    /**
     * Removes all rules from the given environment.
     * Call {@link #save()} to persist.
     */
    @SuppressWarnings("unchecked")
    public void clearRules(String envKey) {
        Map<String, Object> envData = (Map<String, Object>) environments.computeIfAbsent(
                envKey, k -> new HashMap<>());
        envData.put("rules", new ArrayList<>());
    }

    // --- Per-env unified methods (mirror Python's enable_rules / disable_rules / clear_default) ---

    /** Enables rules in a specific environment. */
    public void enableRules(String envKey) { setEnvironmentEnabled(envKey, true); }

    /** Disables rules in a specific environment. */
    public void disableRules(String envKey) { setEnvironmentEnabled(envKey, false); }

    /**
     * Sets the default value for a specific environment.
     * {@code environment=null} sets the flag-level default (not env-scoped).
     */
    public void setDefault(T value, String environment) {
        if (environment == null) {
            this.defaultValue = value;
        } else {
            setEnvironmentDefault(environment, value);
        }
    }

    /** Removes the per-environment default override (does not change the flag-level default). */
    @SuppressWarnings("unchecked")
    public void clearDefault(String envKey) {
        Map<String, Object> envData = (Map<String, Object>) environments.get(envKey);
        if (envData != null) envData.remove("default");
    }

    /** Appends a value to the constrained-values list. */
    public Flag<T> addValue(String name, Object value) {
        if (values == null) values = new ArrayList<>();
        values.add(Map.of("name", name, "value", value));
        return this;
    }

    /** Removes the first value entry whose {@code value} matches. */
    public Flag<T> removeValue(Object value) {
        if (values == null) return this;
        values.removeIf(v -> java.util.Objects.equals(v.get("value"), value));
        return this;
    }

    /** Clears all constrained values (the flag becomes unconstrained). */
    public void clearValues() { this.values = null; }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    void _apply(Flag<?> other) {
        this.id = other.id;
        this.name = other.name;
        this.type = other.type;
        this.defaultValue = (T) other.defaultValue;
        this.values = other.values != null ? new ArrayList<>(other.values) : null;
        this.description = other.description;
        this.environments = other.environments != null ? new HashMap<>(other.environments) : new HashMap<>();
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    @Override
    public String toString() {
        return "Flag{id='" + id + "', type='" + type + "', default=" + defaultValue + "}";
    }
}
