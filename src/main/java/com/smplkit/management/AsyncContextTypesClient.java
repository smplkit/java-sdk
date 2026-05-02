package com.smplkit.management;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link ContextTypesClient}. */
public final class AsyncContextTypesClient {

    private final ContextTypesClient sync;
    private final Executor executor;

    AsyncContextTypesClient(ContextTypesClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /** Construct an unsaved {@link ContextType} (synchronous — no I/O). */
    public ContextType new_(String id) { return sync.new_(id); }

    public ContextType new_(String id, String name,
                            java.util.Map<String, java.util.Map<String, Object>> attributes) {
        return sync.new_(id, name, attributes);
    }

    public CompletableFuture<List<ContextType>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    public CompletableFuture<ContextType> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
