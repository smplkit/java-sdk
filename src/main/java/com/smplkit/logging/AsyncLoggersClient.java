package com.smplkit.logging;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Surface for {@code client.logging.loggers.*} (async).
 *
 * <p>Thin async wrapper around the sync {@link LoggersClient}: CRUD and flush
 * return {@code CompletableFuture<T>}; the {@code new_} factory and the
 * discovery-buffer methods ({@code register} / {@code pendingCount}) stay
 * synchronous since they touch only in-memory state.</p>
 */
public final class AsyncLoggersClient {

    private final LoggersClient sync;
    private final Executor executor;

    AsyncLoggersClient(LoggersClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /**
     * Build a new unsaved {@link Logger} with {@code managed} set to {@code true}.
     * Call {@code save()} (or {@code saveAsync()}) to persist.
     *
     * @param id the identifier for the logger (its normalized name)
     * @return an unsaved {@link Logger} bound to this client
     */
    public Logger new_(String id) {
        return sync.new_(id);
    }

    /**
     * Build a new unsaved {@link Logger} with an explicit {@code managed} flag.
     * Call {@code save()} (or {@code saveAsync()}) to persist.
     *
     * @param id      the identifier for the logger (its normalized name)
     * @param managed when {@code true}, smplkit controls this logger's level at
     *     runtime; when {@code false}, the logger is registered for visibility
     *     without smplkit taking over its level
     * @return an unsaved {@link Logger} bound to this client
     */
    public Logger new_(String id, boolean managed) {
        return sync.new_(id, managed);
    }

    /**
     * Queue a single logger source for registration. Call {@link #flush()} to send.
     *
     * @param item the logger source to queue
     */
    public void register(LoggerSource item) {
        sync.register(item);
    }

    /**
     * Queue logger sources for registration. Call {@link #flush()} to send.
     *
     * @param items the logger sources to queue
     */
    public void register(List<LoggerSource> items) {
        sync.register(items);
    }

    /**
     * Number of sources queued and awaiting flush.
     *
     * @return the count of buffered logger sources not yet sent to the server
     */
    public int pendingCount() {
        return sync.pendingCount();
    }

    /**
     * Drain the buffer and POST pending logger sources to the bulk endpoint.
     *
     * @return a future that completes when the flush round-trip finishes
     */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(sync::flush, executor);
    }

    /**
     * List loggers for the authenticated account using server-default pagination.
     *
     * @return a future that completes with the loggers on the first page
     */
    public CompletableFuture<List<Logger>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    /**
     * List loggers for the authenticated account.
     *
     * @param pageNumber the 1-based page index to fetch; when {@code null}, the
     *     server returns the first page
     * @param pageSize   the maximum number of loggers per page; when
     *     {@code null}, the server applies its default page size
     * @return a future that completes with the loggers on the requested page
     */
    public CompletableFuture<List<Logger>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    /**
     * Fetch a single logger by id.
     *
     * @param id the identifier of the logger to fetch
     * @return a future that completes with the editable {@link Logger} resource,
     *     or fails with {@link com.smplkit.errors.NotFoundError} if no logger
     *     with that id exists
     */
    public CompletableFuture<Logger> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    /**
     * Delete a logger by id.
     *
     * @param id the identifier of the logger to delete
     * @return a future that completes when the delete round-trip finishes
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
