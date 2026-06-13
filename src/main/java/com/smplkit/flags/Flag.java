package com.smplkit.flags;

import com.smplkit.Context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A flag resource — unified runtime handle and management active record.
 *
 * <p>Provides management operations (save, addRule, environment settings)
 * and runtime evaluation via {@link #get}.</p>
 *
 * <p>The type parameter pins the {@link #get} return value (Boolean, String,
 * Number, Object) the same way Python's typed variants (BooleanFlag,
 * StringFlag, NumberFlag, JsonFlag) constrain {@code .get()}.</p>
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

    // --- Typed read-only views ---

    /**
     * Read-only view of per-environment configuration.
     *
     * <p>Mutate via {@link #addRule} / {@link #enableRules} / {@link #disableRules} /
     * {@link #setDefault} (with an {@code environment}) / {@link #clearRules}.</p>
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
     * Read-only view of constrained values.
     *
     * <p>{@code null} means unconstrained. Mutate via {@link #addValue} /
     * {@link #removeValue} / {@link #clearValues}.</p>
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
     * Evaluate this flag and return its current value.
     *
     * <p>Uses the ambient request context (if any). When no context is set, evaluates
     * without context.</p>
     *
     * @return the evaluated flag value, or this flag's default when no environment
     *         override or rule applies
     */
    public T get() {
        return get((List<Context>) null);
    }

    /**
     * Evaluate this flag against the given contexts and return its current value.
     *
     * @param contexts optional list of {@link Context} entities to evaluate targeting
     *                 rules against; when {@code null}, the ambient request context (if
     *                 any) is used
     * @return the evaluated flag value, or this flag's default when no environment
     *         override or rule applies
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
     * Persist this flag to the server.
     *
     * <p>Creates a new flag if unsaved, or updates the existing one.
     * Requires a flags client (i.e. the flag was constructed via
     * {@code client.flags().new*} or returned from {@code client.flags().get}/{@code list}).</p>
     */
    public void save() {
        if (client == null) throw new IllegalStateException("Flag was constructed without a client; cannot save");
        if (createdAt == null) {
            Flag<T> created = client._createFlag(this);
            _apply(created);
        } else {
            Flag<T> updated = client._updateFlag(this);
            _apply(updated);
        }
    }

    /**
     * Async variant of {@link #save()}, running on the common pool.
     *
     * @return a future that completes when the flag has been persisted, or completes
     *         exceptionally if the save fails
     */
    public java.util.concurrent.CompletableFuture<Void> saveAsync() {
        return saveAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /**
     * Async variant of {@link #save()} with a custom executor.
     *
     * @param executor the executor on which to run the blocking save
     * @return a future that completes when the flag has been persisted, or completes
     *         exceptionally if the save fails
     */
    public java.util.concurrent.CompletableFuture<Void> saveAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::save, executor);
    }

    /** Delete this flag from the server. */
    public void delete() {
        if (client == null || id == null) {
            throw new IllegalStateException("Flag was constructed without a client or id; cannot delete");
        }
        client.delete(id);
    }

    /**
     * Async variant of {@link #delete()}, running on the common pool.
     *
     * @return a future that completes when the flag has been deleted, or completes
     *         exceptionally if the delete fails
     */
    public java.util.concurrent.CompletableFuture<Void> deleteAsync() {
        return deleteAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /**
     * Async variant of {@link #delete()} with a custom executor.
     *
     * @param executor the executor on which to run the blocking delete
     * @return a future that completes when the flag has been deleted, or completes
     *         exceptionally if the delete fails
     */
    public java.util.concurrent.CompletableFuture<Void> deleteAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::delete, executor);
    }

    // ------------------------------------------------------------------
    // Management: local mutations
    // ------------------------------------------------------------------

    /**
     * Append a rule to a specific environment.
     *
     * <p>The {@code builtRule} map must include an {@code "environment"} key.
     * Call {@link #save()} to persist.</p>
     *
     * @return this for chaining
     */
    @SuppressWarnings("unchecked")
    public Flag<T> addRule(Map<String, Object> builtRule) {
        String envKey = (String) builtRule.get("environment");
        if (envKey == null) {
            throw new IllegalArgumentException(
                    "Built rule must include 'environment' key. "
                    + "Use Rule(..., environment=\"env_key\").when(...).serve(...)");
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
     * Enable rule evaluation in every environment configured on this flag.
     * Call {@link #save()} to persist.
     */
    public void enableRules() { enableRules(null); }

    /**
     * Enable rule evaluation. Call {@link #save()} to persist.
     *
     * <p>With an {@code environment} scopes to that single environment; with
     * {@code null}, enables rules in every environment configured on this flag.</p>
     */
    public void enableRules(String environment) {
        if (environment == null) {
            for (String envKey : new ArrayList<>(environments.keySet())) {
                setEnvironmentEnabled(envKey, true);
            }
        } else {
            setEnvironmentEnabled(environment, true);
        }
    }

    /**
     * Disable rule evaluation (kill switch) in every environment configured on
     * this flag. Call {@link #save()} to persist.
     */
    public void disableRules() { disableRules(null); }

    /**
     * Disable rule evaluation (kill switch). Call {@link #save()} to persist.
     *
     * <p>With an {@code environment} scopes to that single environment; with
     * {@code null}, disables rules in every environment configured on this flag.
     * When disabled, {@link #get} skips rules and returns the env-specific default
     * (or the flag's base default).</p>
     */
    public void disableRules(String environment) {
        if (environment == null) {
            for (String envKey : new ArrayList<>(environments.keySet())) {
                setEnvironmentEnabled(envKey, false);
            }
        } else {
            setEnvironmentEnabled(environment, false);
        }
    }

    /**
     * Set the flag's default served value.
     *
     * <p>With {@code environment=null} (the default), updates the flag-level default
     * used when no environment-specific override applies. With an {@code environment},
     * sets the per-environment default served when no rule matches.</p>
     *
     * <p>Call {@link #save()} to persist.</p>
     */
    public void setDefault(T value, String environment) {
        if (environment == null) {
            this.defaultValue = value;
        } else {
            setEnvironmentDefault(environment, value);
        }
    }

    /**
     * Clear the per-environment default override on {@code environment}.
     *
     * <p>After clearing, the environment falls back to the flag's base default
     * when no rule matches. Call {@link #save()} to persist.</p>
     */
    @SuppressWarnings("unchecked")
    public void clearDefault(String environment) {
        Map<String, Object> envData = (Map<String, Object>) environments.get(environment);
        if (envData != null) envData.remove("default");
    }

    /**
     * Remove rules from every environment configured on this flag.
     * Call {@link #save()} to persist.
     */
    public void clearRules() { clearRules(null); }

    /**
     * Remove rules. Call {@link #save()} to persist.
     *
     * <p>With an {@code environment} scopes to that single environment; with
     * {@code null}, removes rules from every environment configured on this flag.</p>
     */
    @SuppressWarnings("unchecked")
    public void clearRules(String environment) {
        if (environment == null) {
            for (String envKey : new ArrayList<>(environments.keySet())) {
                // Keys come straight from the map, so the entry always exists.
                Map<String, Object> envData = (Map<String, Object>) environments.get(envKey);
                envData.put("rules", new ArrayList<>());
            }
        } else {
            Map<String, Object> envData = (Map<String, Object>) environments.computeIfAbsent(
                    environment, k -> new HashMap<>());
            envData.put("rules", new ArrayList<>());
        }
    }

    /**
     * Append a constrained value to the flag's values list.
     *
     * @param name  human-readable label for the value entry
     * @param value the value to allow the flag to serve
     * @return this flag, so calls can be chained
     */
    public Flag<T> addValue(String name, Object value) {
        if (values == null) values = new ArrayList<>();
        values.add(Map.of("name", name, "value", value));
        return this;
    }

    /**
     * Remove the first values entry whose {@code value} field matches.
     *
     * @param value the value to remove; entries are matched on their {@code value} field,
     *              and the first match is removed while others are left in place
     * @return this flag, so calls can be chained
     */
    public Flag<T> removeValue(Object value) {
        if (values == null) return this;
        values.removeIf(v -> java.util.Objects.equals(v.get("value"), value));
        return this;
    }

    /** Set values to {@code null} (unconstrained). Call {@link #save()} to persist. */
    public void clearValues() { this.values = null; }

    // --- Private per-env helpers backing the public mutators ---

    @SuppressWarnings("unchecked")
    private void setEnvironmentEnabled(String envKey, boolean enabled) {
        Map<String, Object> envData = (Map<String, Object>) environments.computeIfAbsent(
                envKey, k -> new HashMap<>());
        envData.put("enabled", enabled);
    }

    @SuppressWarnings("unchecked")
    private void setEnvironmentDefault(String envKey, Object defaultVal) {
        Map<String, Object> envData = (Map<String, Object>) environments.computeIfAbsent(
                envKey, k -> new HashMap<>());
        envData.put("default", defaultVal);
    }

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
