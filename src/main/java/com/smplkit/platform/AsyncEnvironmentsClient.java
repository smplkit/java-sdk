package com.smplkit.platform;

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

    /** Construct an unsaved {@link Environment} with no color and the default
     * {@link EnvironmentClassification#STANDARD} classification (synchronous — no I/O). */
    public Environment new_(String id, String name) {
        return sync.new_(id, name);
    }

    /** Construct an unsaved {@link Environment} (synchronous — no I/O). */
    public Environment new_(String id, String name, String color,
                            EnvironmentClassification classification) {
        return sync.new_(id, name, color, classification);
    }

    /**
     * Lists environments using the server's default pagination (first page, up to 1000 rows).
     *
     * @return a future completing with the environments on the first page
     */
    public CompletableFuture<List<Environment>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    /**
     * Lists a single page of environments. Pass {@code null} for either argument to use
     * the server default ({@code page[number]=1}, {@code page[size]=1000}).
     *
     * @param pageNumber 1-based page to fetch. Defaults to the first page.
     * @param pageSize maximum items per page; server default when omitted.
     * @return a future completing with the environments on the requested page
     */
    public CompletableFuture<List<Environment>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    /**
     * Fetch a single environment by id.
     *
     * @param id identifier of the environment to fetch
     * @return a future completing with the matching environment, or completing
     *     exceptionally with {@link com.smplkit.errors.NotFoundError} if no
     *     environment with that id exists
     */
    public CompletableFuture<Environment> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    /**
     * Delete an environment by id.
     *
     * @param id identifier of the environment to delete
     * @return a future completing when the delete round-trip finishes
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
