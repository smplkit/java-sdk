package com.smplkit.management;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link EnvironmentsClient}. */
public final class AsyncEnvironmentsClient {

    private final EnvironmentsClient sync;
    private final Executor executor;

    AsyncEnvironmentsClient(EnvironmentsClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /** Construct an unsaved {@link Environment} (synchronous — no I/O). */
    public Environment new_(String id, String name, String color,
                            EnvironmentClassification classification) {
        return sync.new_(id, name, color, classification);
    }

    public CompletableFuture<List<Environment>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    public CompletableFuture<Environment> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
