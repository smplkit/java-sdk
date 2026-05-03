package com.smplkit.management;

import com.smplkit.Context;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link ContextsClient}. */
public final class AsyncContextsClient {

    private final ContextsClient sync;
    private final Executor executor;

    AsyncContextsClient(ContextsClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    public CompletableFuture<Void> register(List<Context> contexts) {
        return CompletableFuture.runAsync(() -> sync.register(contexts), executor);
    }

    public CompletableFuture<Void> register(Context context) {
        return CompletableFuture.runAsync(() -> sync.register(context), executor);
    }

    /** Async flush of pending registrations. */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(sync::flush, executor);
    }

    public CompletableFuture<List<ContextEntity>> list(String type) {
        return CompletableFuture.supplyAsync(() -> sync.list(type), executor);
    }

    public CompletableFuture<ContextEntity> get(String compositeId) {
        return CompletableFuture.supplyAsync(() -> sync.get(compositeId), executor);
    }

    public CompletableFuture<ContextEntity> get(String type, String key) {
        return CompletableFuture.supplyAsync(() -> sync.get(type, key), executor);
    }

    public CompletableFuture<Void> delete(String compositeId) {
        return CompletableFuture.runAsync(() -> sync.delete(compositeId), executor);
    }

    public CompletableFuture<Void> delete(String type, String key) {
        return CompletableFuture.runAsync(() -> sync.delete(type, key), executor);
    }
}
