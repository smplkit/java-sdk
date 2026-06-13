package com.smplkit.platform;

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

    /**
     * Buffer contexts for registration.
     *
     * <p>Buffered contexts are sent in batches: a background flush kicks in once
     * enough have accumulated. Call {@link #flush()} to send any remainder
     * immediately.</p>
     *
     * @param contexts the contexts to register
     * @return a future completing once the contexts have been buffered
     */
    public CompletableFuture<Void> register(List<Context> contexts) {
        return CompletableFuture.runAsync(() -> sync.register(contexts), executor);
    }

    /**
     * Buffer a single context for registration.
     *
     * <p>Buffered contexts are sent in batches: a background flush kicks in once
     * enough have accumulated. Call {@link #flush()} to send any remainder
     * immediately.</p>
     *
     * @param context the context to register
     * @return a future completing once the context has been buffered
     */
    public CompletableFuture<Void> register(Context context) {
        return CompletableFuture.runAsync(() -> sync.register(context), executor);
    }

    /** Async flush of pending registrations. */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(sync::flush, executor);
    }

    /** Number of observations queued and awaiting flush (synchronous — no I/O). */
    public int pendingCount() {
        return sync.pendingCount();
    }

    /**
     * Lists contexts of the given type using the server's default pagination (first page, up to 1000 rows).
     *
     * @param type context type to list (for example {@code "user"})
     * @return a future completing with the contexts of the given type on the first page
     */
    public CompletableFuture<List<ContextEntity>> list(String type) {
        return CompletableFuture.supplyAsync(() -> sync.list(type), executor);
    }

    /**
     * Lists a single page of contexts of the given type. Pass {@code null} for either
     * pagination argument to use the server default ({@code page[number]=1},
     * {@code page[size]=1000}).
     *
     * @param type context type to list (for example {@code "user"})
     * @param pageNumber 1-based page to fetch. Defaults to the first page.
     * @param pageSize maximum items per page; server default when omitted.
     * @return a future completing with the contexts of the given type on the requested page
     */
    public CompletableFuture<List<ContextEntity>> list(String type, Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(type, pageNumber, pageSize), executor);
    }

    /**
     * Fetch a single context by composite {@code "type:key"} id.
     *
     * @param compositeId the composite context id in {@code "type:key"} form
     * @return a future completing with the matching context, or completing
     *     exceptionally with {@link com.smplkit.errors.NotFoundError} if no
     *     context with that id exists
     */
    public CompletableFuture<ContextEntity> get(String compositeId) {
        return CompletableFuture.supplyAsync(() -> sync.get(compositeId), executor);
    }

    /**
     * Fetch a single context by type and key.
     *
     * @param type the context type
     * @param key  the context key
     * @return a future completing with the matching context, or completing
     *     exceptionally with {@link com.smplkit.errors.NotFoundError} if no
     *     such context exists
     */
    public CompletableFuture<ContextEntity> get(String type, String key) {
        return CompletableFuture.supplyAsync(() -> sync.get(type, key), executor);
    }

    /**
     * Delete a single context by composite {@code "type:key"} id.
     *
     * @param compositeId the composite context id in {@code "type:key"} form
     * @return a future completing when the delete round-trip finishes
     */
    public CompletableFuture<Void> delete(String compositeId) {
        return CompletableFuture.runAsync(() -> sync.delete(compositeId), executor);
    }

    /**
     * Delete a single context by type and key.
     *
     * @param type the context type
     * @param key  the context key
     * @return a future completing when the delete round-trip finishes
     */
    public CompletableFuture<Void> delete(String type, String key) {
        return CompletableFuture.runAsync(() -> sync.delete(type, key), executor);
    }
}
