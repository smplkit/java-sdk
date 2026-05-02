package com.smplkit.logging;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link LoggersClient}. */
public final class AsyncLoggersClient {

    private final LoggersClient sync;
    private final Executor executor;

    public AsyncLoggersClient(LoggersClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    public Logger new_(String id) { return sync.new_(id); }
    public Logger new_(String id, boolean managed) { return sync.new_(id, managed); }

    public CompletableFuture<Logger> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    public CompletableFuture<List<Logger>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }

    public CompletableFuture<Void> registerSources(List<LoggerSource> sources) {
        return CompletableFuture.runAsync(() -> sync.registerSources(sources), executor);
    }
}
