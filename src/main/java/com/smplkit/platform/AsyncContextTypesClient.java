package com.smplkit.platform;

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

    /**
     * Construct an unsaved {@link ContextType} with an explicit name and attributes
     * (synchronous — no I/O).
     *
     * @param id stable, human-readable identifier for the context type
     *     (for example {@code "user"})
     * @param name display name shown in the Console; a name derived from
     *     {@code id} is used when {@code null}
     * @param attributes known-attribute slots, keyed by attribute name, with a
     *     metadata map per slot; may be {@code null} for no declared attributes
     * @return an unsaved {@link ContextType} bound to this client
     */
    public ContextType new_(String id, String name,
                            java.util.Map<String, java.util.Map<String, Object>> attributes) {
        return sync.new_(id, name, attributes);
    }

    /**
     * Lists context types using the server's default pagination (first page, up to 1000 rows).
     *
     * @return a future completing with the context types on the first page
     */
    public CompletableFuture<List<ContextType>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    /**
     * Lists a single page of context types. Pass {@code null} for either argument to use
     * the server default ({@code page[number]=1}, {@code page[size]=1000}).
     *
     * @param pageNumber 1-based page to fetch. Defaults to the first page.
     * @param pageSize maximum items per page; server default when omitted.
     * @return a future completing with the context types on the requested page
     */
    public CompletableFuture<List<ContextType>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    /**
     * Fetch a single context type by id.
     *
     * @param id identifier of the context type to fetch
     * @return a future completing with the matching context type, or completing
     *     exceptionally with {@link com.smplkit.errors.NotFoundError} if no
     *     context type with that id exists
     */
    public CompletableFuture<ContextType> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    /**
     * Delete a context type by id.
     *
     * @param id identifier of the context type to delete
     * @return a future completing when the delete round-trip finishes
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
