package com.smplkit.management;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link ServicesClient}. */
public final class AsyncServicesClient {

    private final ServicesClient sync;
    private final Executor executor;

    AsyncServicesClient(ServicesClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /** Construct an unsaved {@link Service} (synchronous — no I/O). */
    public Service new_(String id, String name) {
        return sync.new_(id, name);
    }

    public CompletableFuture<List<Service>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    public CompletableFuture<List<Service>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    public CompletableFuture<Service> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
