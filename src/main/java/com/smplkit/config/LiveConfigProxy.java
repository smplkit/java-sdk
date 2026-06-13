package com.smplkit.config;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A live, dict-like view of resolved config values.
 *
 * <p>Returned by {@link ConfigClient#subscribe}. Always reflects the latest
 * server-pushed state — every read sees current values.</p>
 *
 * <p>Implements the {@link Map} API: {@code proxy.get("key")},
 * {@code proxy.containsKey("key")}, {@code proxy.size()}, iteration over
 * {@code proxy.keySet()}, {@code proxy.values()}, {@code proxy.entrySet()},
 * {@code proxy.getOrDefault(key, default)}. Read-only; any write attempt
 * raises.</p>
 *
 * <p>For typed access via a Java POJO, use {@link ConfigClient#bind}
 * instead — bound instances stay live on the same WebSocket cache, with
 * field access typed by the POJO class.</p>
 */
public final class LiveConfigProxy implements Map<String, Object> {

    private static final String READ_ONLY_MSG =
            "LiveConfigProxy is read-only; cannot set. Edit config values via client.config().get(id) + save().";

    private final ConfigClient client;
    private final String configId;

    LiveConfigProxy(ConfigClient client, String configId) {
        this.client = client;
        this.configId = configId;
    }

    /**
     * Returns the config id this proxy reads through.
     *
     * @return the config identifier (slug) this proxy is scoped to
     */
    public String configId() {
        return configId;
    }

    /**
     * Register a change listener scoped to this config.
     *
     * <p>Fires on any change to this config. Equivalent to
     * {@code client.config().onChange(configId, fn)}; offered as sugar so
     * callers who already have a live proxy can register listeners without
     * re-stating the config id.</p>
     *
     * @param listener invoked with a {@link ConfigChangeEvent} when this config
     *     changes
     */
    public void onChange(Consumer<ConfigChangeEvent> listener) {
        client.onChange(configId, listener);
    }

    /**
     * Register a change listener scoped to a single item on this config.
     *
     * <p>Fires only when {@code itemKey} changes. Equivalent to
     * {@code client.config().onChange(configId, itemKey, fn)}.</p>
     *
     * @param itemKey  the item key within this config to restrict the listener to
     * @param listener invoked with a {@link ConfigChangeEvent} when that item
     *     changes
     */
    public void onChange(String itemKey, Consumer<ConfigChangeEvent> listener) {
        client.onChange(configId, itemKey, listener);
    }

    /** Read the current resolved values from the client cache. */
    private Map<String, Object> currentValues() {
        return client._getResolvedCache(configId);
    }

    // --- Map<String, Object> read-only interface ---

    @Override
    public int size() {
        return currentValues().size();
    }

    @Override
    public boolean isEmpty() {
        return currentValues().isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return currentValues().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return currentValues().containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return currentValues().get(key);
    }

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
        return "LiveConfigProxy(config_id=" + configId + ")";
    }
}
