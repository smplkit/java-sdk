package com.smplkit.logging;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Surface for {@code client.logging.logGroups.*} (async).
 *
 * <p>Thin async wrapper around the sync {@link LogGroupsClient}: CRUD returns
 * {@code CompletableFuture<T>}; the {@code new_} factory stays synchronous.</p>
 */
public final class AsyncLogGroupsClient {

    private final LogGroupsClient sync;
    private final Executor executor;

    AsyncLogGroupsClient(LogGroupsClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /**
     * Build a new unsaved {@link LogGroup}. Call {@code save()} (or
     * {@code saveAsync()}) to persist.
     *
     * <p>The display name defaults to a title-cased version of {@code id}, and
     * the group is created as top-level.</p>
     *
     * @param id the identifier for the log group
     * @return an unsaved {@link LogGroup} bound to this client
     */
    public LogGroup new_(String id) {
        return sync.new_(id);
    }

    /**
     * Build a new unsaved {@link LogGroup} with an explicit name and parent group.
     * Call {@code save()} (or {@code saveAsync()}) to persist.
     *
     * @param id    the identifier for the log group
     * @param name  the human-readable display name; when {@code null}, defaults
     *     to a title-cased version of {@code id}
     * @param group the identifier of the parent log group when nesting groups;
     *     {@code null} for a top-level group
     * @return an unsaved {@link LogGroup} bound to this client
     */
    public LogGroup new_(String id, String name, String group) {
        return sync.new_(id, name, group);
    }

    /**
     * List log groups for the authenticated account using server-default pagination.
     *
     * @return a future that completes with the log groups on the first page
     */
    public CompletableFuture<List<LogGroup>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    /**
     * List log groups for the authenticated account.
     *
     * @param pageNumber the 1-based page index to fetch; when {@code null}, the
     *     server returns the first page
     * @param pageSize   the maximum number of log groups per page; when
     *     {@code null}, the server applies its default page size
     * @return a future that completes with the log groups on the requested page
     */
    public CompletableFuture<List<LogGroup>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    /**
     * Fetch a single log group by id.
     *
     * @param id the identifier of the log group to fetch
     * @return a future that completes with the editable {@link LogGroup}
     *     resource, or fails with {@link com.smplkit.errors.NotFoundError} if no
     *     log group with that id exists
     */
    public CompletableFuture<LogGroup> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    /**
     * Delete a log group by id.
     *
     * @param id the identifier of the log group to delete
     * @return a future that completes when the delete round-trip finishes
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
