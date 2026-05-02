package com.smplkit.management;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Active-record account-settings model.
 *
 * <p>The wire format is opaque JSON. Documented keys are exposed as typed properties;
 * unknown keys are preserved through {@link #getRaw()}. Call {@link #save()} to write back.</p>
 */
public final class AccountSettings {

    private AccountSettingsClient client;
    private Map<String, Object> data;

    AccountSettings(AccountSettingsClient client, Map<String, Object> data) {
        this.client = client;
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
    }

    /** The full settings dict. Mutations are persisted on {@link #save()}. */
    public Map<String, Object> getRaw() {
        return data;
    }

    public void setRaw(Map<String, Object> value) {
        this.data = value != null ? new HashMap<>(value) : new HashMap<>();
    }

    /** Canonical ordering of STANDARD environments. Empty list if unset. */
    @SuppressWarnings("unchecked")
    public List<String> getEnvironmentOrder() {
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

    public void setEnvironmentOrder(List<String> value) {
        data.put("environment_order", value != null ? new ArrayList<>(value) : new ArrayList<>());
    }

    /**
     * Writes back the full settings object. Applies the server response back.
     */
    public void save() {
        if (client == null) throw new IllegalStateException("AccountSettings not bound to a client");
        AccountSettings saved = client._save(data);
        _apply(saved);
    }

    /** Async variant of {@link #save()} (rule 12). */
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
        return "AccountSettings{" + data + "}";
    }
}
