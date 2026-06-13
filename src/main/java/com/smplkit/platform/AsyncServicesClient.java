package com.smplkit.platform;

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

    /**
     * Lists services using the server's default pagination (first page, up to 1000 rows).
     *
     * @return a future completing with the services on the first page
     */
    public CompletableFuture<List<Service>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    /**
     * Lists a single page of services. Pass {@code null} for either argument to use the
     * server default ({@code page[number]=1}, {@code page[size]=1000}).
     *
     * @param pageNumber 1-based page to fetch. Defaults to the first page.
     * @param pageSize maximum items per page; server default when omitted.
     * @return a future completing with the services on the requested page
     */
    public CompletableFuture<List<Service>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    /**
     * Fetch a single service by id.
     *
     * @param id identifier of the service to fetch
     * @return a future completing with the matching service, or completing
     *     exceptionally with {@link com.smplkit.errors.NotFoundError} if no
     *     service with that id exists
     */
    public CompletableFuture<Service> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    /**
     * Delete a service by id.
     *
     * @param id identifier of the service to delete
     * @return a future completing when the delete round-trip finishes
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
