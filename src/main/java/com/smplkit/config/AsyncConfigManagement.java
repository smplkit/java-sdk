package com.smplkit.config;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link ConfigManagement}. */
public final class AsyncConfigManagement {

    private final ConfigManagement sync;
    private final Executor executor;

    public AsyncConfigManagement(ConfigManagement sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /** Construct an unsaved {@link Config} (synchronous — no I/O). */
    public Config new_(String id) { return sync.new_(id); }

    public Config new_(String id, String name, String description, String parent) {
        return sync.new_(id, name, description, parent);
    }

    public Config new_(String id, String name, String description, Config parent) {
        return sync.new_(id, name, description, parent);
    }

    public CompletableFuture<Config> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    public CompletableFuture<List<Config>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    public CompletableFuture<List<Config>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }

    // ------------------------------------------------------------------
    // Discovery passthroughs (sync internally — operate on an in-memory
    // buffer and only hit the network on flush).
    // ------------------------------------------------------------------

    public void registerConfig(String configId, String service, String environment,
                               String parent, String name, String description) {
        sync.registerConfig(configId, service, environment, parent, name, description);
    }

    public void registerConfigItem(String configId, String itemKey, String itemType,
                                   Object defaultValue, String description) {
        sync.registerConfigItem(configId, itemKey, itemType, defaultValue, description);
    }

    public int pendingCount() { return sync.pendingCount(); }

    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(sync::flush, executor);
    }
}
