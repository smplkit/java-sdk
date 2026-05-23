package com.smplkit.config;

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
 * <p>Every read goes through the {@link ConfigClient}'s resolved-config
 * cache, so WebSocket updates are picked up automatically — there is no
 * {@code subscribe()} step. The proxy is identity-stable: calling
 * {@link ConfigClient#get(String)} for the same id returns the same
 * {@code LiveConfigProxy} instance.</p>
 *
 * <p>Implements {@link Map} for ergonomic {@code proxy.get("database.host")},
 * {@code containsKey}, and iteration. Mutation paths
 * ({@link #put}, {@link #remove}, {@link #clear}, {@link #putAll}) throw
 * {@link UnsupportedOperationException} pointing at
 * {@code client.manage().config} for legitimate mutations.</p>
 *
 * <p>For declarative, typed access, use {@link ConfigClient#bind} instead —
 * the bound target stays live on the same WebSocket-driven cache, with
 * plain field access in place of {@code proxy.get(key)}.</p>
 */
public final class LiveConfigProxy implements Map<String, Object> {

    private static final String READ_ONLY_MSG =
            "LiveConfigProxy is read-only; use client.manage().config to mutate config values";

    private final ConfigClient client;
    private final String configId;

    LiveConfigProxy(ConfigClient client, String configId) {
        this.client = client;
        this.configId = configId;
    }

    /** Returns the config id this proxy reads through. */
    public String configId() { return configId; }

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

    // --- Mutation paths: all raise ---

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
