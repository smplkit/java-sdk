package com.smplkit.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A live, dict-like, read-only proxy over a runtime config.
 *
 * <p>Mirrors Python's {@code LiveConfigProxy}: every read goes through the
 * {@link ConfigClient}'s resolved-config cache, so WebSocket updates are
 * picked up automatically — there is no {@code subscribe()} step. The proxy
 * is identity-stable: calling {@link ConfigClient#get(String)} for the same id
 * returns the same {@code LiveConfigProxy} instance.</p>
 *
 * <p>Implements {@link Map} for ergonomic {@code proxy.get("database.host")} /
 * {@code containsKey} / iteration. Mutation paths
 * ({@link #put}, {@link #remove}, {@link #clear}, {@link #putAll}) raise
 * {@link UnsupportedOperationException} pointing at
 * {@code client.manage().config} for legitimate mutations.</p>
 *
 * <p>For typed access, call {@link #into(Class)} to convert the current
 * resolved values into a model instance (dot-notation keys are expanded into
 * nested objects).</p>
 */
public final class LiveConfigProxy implements Map<String, Object> {

    private static final String READ_ONLY_MSG =
            "LiveConfigProxy is read-only; use client.manage().config to mutate config values";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigClient client;
    private final String configId;

    LiveConfigProxy(ConfigClient client, String configId) {
        this.client = client;
        this.configId = configId;
    }

    /** Returns the config id this proxy reads through. */
    public String configId() { return configId; }

    /**
     * Convert the current resolved values into a typed model instance.
     *
     * <p>Dot-notation keys (e.g. {@code "database.host"}) are expanded into
     * nested objects before mapping to {@code modelClass}.</p>
     */
    public <T> T into(Class<T> modelClass) {
        Map<String, Object> values = currentValues();
        Map<String, Object> nested = ConfigClient.unflatten(values);
        return MAPPER.convertValue(nested, modelClass);
    }

    /**
     * Sugar for {@code client.onChange(configId, fn)} — fires for any item
     * change on this config.
     */
    public void onChange(Consumer<ConfigChangeEvent> listener) {
        client.onChange(configId, listener);
    }

    /**
     * Sugar for {@code client.onChange(configId, itemKey, fn)} — fires only
     * when {@code itemKey} changes on this config.
     */
    public void onChange(String itemKey, Consumer<ConfigChangeEvent> listener) {
        client.onChange(configId, itemKey, listener);
    }

    // ------------------------------------------------------------------
    // Typed getters (ADR-037 §2.13)
    //
    // Each registers the item (key, type, default, description) on first
    // call within the process, then returns the resolved value. When the
    // resolved value cannot be coerced to the getter's type — including
    // the "not yet set on the server" case — the in-code default is
    // returned and a structured warning is logged.
    // ------------------------------------------------------------------

    private void registerItem(String key, String itemType, Object defaultValue, String description) {
        client._observeItemDeclaration(configId, key, itemType, defaultValue, description);
    }

    /** Read a BOOLEAN item, registering the declaration on first call. */
    public boolean getBool(String key, boolean defaultValue) { return getBool(key, defaultValue, null); }

    public boolean getBool(String key, boolean defaultValue, String description) {
        registerItem(key, "BOOLEAN", defaultValue, description);
        Map<String, Object> values = currentValues();
        if (!values.containsKey(key)) return defaultValue;
        Object value = values.get(key);
        if (value instanceof Boolean b) return b;
        warnMismatch(key, "BOOLEAN", value);
        return defaultValue;
    }

    /** Read a NUMBER item as int, registering the declaration on first call. */
    public int getInt(String key, int defaultValue) { return getInt(key, defaultValue, null); }

    public int getInt(String key, int defaultValue, String description) {
        registerItem(key, "NUMBER", defaultValue, description);
        Map<String, Object> values = currentValues();
        if (!values.containsKey(key)) return defaultValue;
        Object value = values.get(key);
        if (!(value instanceof Boolean)) {
            if (value instanceof Integer i) return i;
            if (value instanceof Long l) return l.intValue();
            if (value instanceof Double d && d == Math.floor(d)) return d.intValue();
            if (value instanceof Float f && f == Math.floor(f)) return f.intValue();
        }
        warnMismatch(key, "NUMBER (int)", value);
        return defaultValue;
    }

    /** Read a NUMBER item as double, registering the declaration on first call. */
    public double getFloat(String key, double defaultValue) { return getFloat(key, defaultValue, null); }

    public double getFloat(String key, double defaultValue, String description) {
        registerItem(key, "NUMBER", defaultValue, description);
        Map<String, Object> values = currentValues();
        if (!values.containsKey(key)) return defaultValue;
        Object value = values.get(key);
        if (!(value instanceof Boolean) && value instanceof Number n) return n.doubleValue();
        warnMismatch(key, "NUMBER (float)", value);
        return defaultValue;
    }

    /** Read a STRING item, registering the declaration on first call. */
    public String getString(String key, String defaultValue) { return getString(key, defaultValue, null); }

    public String getString(String key, String defaultValue, String description) {
        registerItem(key, "STRING", defaultValue, description);
        Map<String, Object> values = currentValues();
        if (!values.containsKey(key)) return defaultValue;
        Object value = values.get(key);
        if (value instanceof String s) return s;
        warnMismatch(key, "STRING", value);
        return defaultValue;
    }

    /** Read a JSON item, registering the declaration on first call. */
    public Object getJson(String key, Object defaultValue) { return getJson(key, defaultValue, null); }

    public Object getJson(String key, Object defaultValue, String description) {
        registerItem(key, "JSON", defaultValue, description);
        Map<String, Object> values = currentValues();
        return values.containsKey(key) ? values.get(key) : defaultValue;
    }

    private void warnMismatch(String key, String expected, Object value) {
        String got = value != null ? value.getClass().getSimpleName() : "null";
        java.util.logging.Logger.getLogger("smplkit.config").warning(
                "config " + configId + " item " + key + ": expected " + expected
                        + ", got " + got + "; returning default");
    }

    private Map<String, Object> currentValues() {
        return client._getResolvedCache(configId);
    }

    // --- Map<String, Object> read-only interface ---

    @Override
    public int size() { return currentValues().size(); }

    @Override
    public boolean isEmpty() { return currentValues().isEmpty(); }

    @Override
    public boolean containsKey(Object key) { return currentValues().containsKey(key); }

    @Override
    public boolean containsValue(Object value) { return currentValues().containsValue(value); }

    @Override
    public Object get(Object key) { return currentValues().get(key); }

    @Override
    public Object getOrDefault(Object key, Object defaultValue) {
        return currentValues().getOrDefault(key, defaultValue);
    }

    @Override
    public Set<String> keySet() {
        // Defensive copy so iterators don't throw ConcurrentModificationException
        // when WebSocket events update the underlying cache mid-iteration.
        return new HashSet<>(currentValues().keySet());
    }

    @Override
    public Collection<Object> values() {
        return new HashMap<>(currentValues()).values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        Map<String, Object> snap = new HashMap<>(currentValues());
        Set<Entry<String, Object>> result = new HashSet<>();
        for (Entry<String, Object> e : snap.entrySet()) {
            result.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        }
        return result;
    }

    // --- Mutation paths: all raise (mirrors Python's setattr/setitem block) ---

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException(READ_ONLY_MSG);
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException(READ_ONLY_MSG);
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        throw new UnsupportedOperationException(READ_ONLY_MSG);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException(READ_ONLY_MSG);
    }

    @Override
    public String toString() {
        return "LiveConfigProxy[configId=" + configId + ", values=" + currentValues() + "]";
    }
}
