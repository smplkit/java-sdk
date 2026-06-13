package com.smplkit.account;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Active-record account-settings model.
 *
 * <p>The wire format is opaque JSON. Documented keys are exposed as
 * typed properties; unknown keys live in {@link #raw}. {@link #save()}
 * writes the full settings object back.</p>
 */
public final class AccountSettings {

    private SettingsClient client;
    private Map<String, Object> data;

    AccountSettings(SettingsClient client, Map<String, Object> data) {
        this.client = client;
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
    }

    /**
     * The full settings map. Mutations are persisted on {@link #save()}.
     *
     * @return the live settings map backing this record
     */
    public Map<String, Object> raw() {
        return data;
    }

    /**
     * Replaces the full settings map. The change is persisted on {@link #save()}.
     *
     * @param value the new settings map; {@code null} clears it to an empty map
     */
    public void setRaw(Map<String, Object> value) {
        this.data = value != null ? new HashMap<>(value) : new HashMap<>();
    }

    /**
     * Canonical ordering of STANDARD environments. Empty list if unset.
     *
     * @return the environment ordering, or an empty list when unset
     */
    @SuppressWarnings("unchecked")
    public List<String> environmentOrder() {
        Object val = data.get("environment_order");
        if (val instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) result.add(s);
            }
            return result;
        }
        return Collections.emptyList();
    }

    /**
     * Sets the canonical ordering of STANDARD environments. The change is
     * persisted on {@link #save()}.
     *
     * @param value the environment ids in their canonical order; {@code null}
     *     clears the ordering to an empty list
     */
    public void setEnvironmentOrder(List<String> value) {
        data.put("environment_order", value != null ? new ArrayList<>(value) : new ArrayList<>());
    }

    /**
     * Writes back the full settings object. Applies the server response back.
     */
    public void save() {
        if (client == null) {
            throw new IllegalStateException("AccountSettings was constructed without a client; cannot save");
        }
        AccountSettings other = client._save(data);
        _apply(other);
    }

    /** Async variant of {@link #save()}. */
    public java.util.concurrent.CompletableFuture<Void> saveAsync() {
        return saveAsync(java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Async variant of {@link #save()} with a custom executor. */
    public java.util.concurrent.CompletableFuture<Void> saveAsync(java.util.concurrent.Executor executor) {
        return java.util.concurrent.CompletableFuture.runAsync(this::save, executor);
    }

    void _apply(AccountSettings other) {
        this.data = new HashMap<>(other.data);
    }

    @Override
    public String toString() {
        return "AccountSettings(" + data + ")";
    }
}
