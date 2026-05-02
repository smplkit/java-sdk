package com.smplkit.logging;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link LogGroupsClient}. */
public final class AsyncLogGroupsClient {

    private final LogGroupsClient sync;
    private final Executor executor;

    public AsyncLogGroupsClient(LogGroupsClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    public LogGroup new_(String id) { return sync.new_(id); }
    public LogGroup new_(String id, String name, String parentGroup) {
        return sync.new_(id, name, parentGroup);
    }

    public CompletableFuture<LogGroup> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    public CompletableFuture<List<LogGroup>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
