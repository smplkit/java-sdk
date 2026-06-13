package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Async wrapper around {@link RunsClient} ({@code asyncJobs.runs}). */
public final class AsyncRunsClient {

    private final RunsClient sync;
    private final Executor executor;

    AsyncRunsClient(RunsClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /**
     * List past runs, most recent first, using default paging across all jobs.
     *
     * @return a future of the runs in the first page. It completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<List<Run>> list() {
        return CompletableFuture.supplyAsync(() -> checked(sync::list), executor);
    }

    /**
     * List past runs, most recent first. Cursor paginated.
     *
     * @param input filters and paging for the listing. {@code job} returns
     *     only runs of the job with that id ({@code null} lists across all
     *     jobs); {@code pageSize} is the max runs per page ({@code null} uses
     *     the server default); {@code after} is an opaque cursor from a
     *     previous page ({@code null} starts from the first page)
     * @return a future of the runs in this page. It completes exceptionally
     *     with a {@code CompletionException} wrapping the underlying
     *     {@code ApiException} on failure
     */
    public CompletableFuture<List<Run>> list(ListRunsInput input) {
        return CompletableFuture.supplyAsync(() -> checked(() -> sync.list(input)), executor);
    }

    /**
     * Fetch a single run by its id.
     *
     * @param runId identifier of the run to fetch
     * @return a future of the matching {@link Run}. It completes exceptionally
     *     with a {@code CompletionException} wrapping the underlying
     *     {@code ApiException} on failure
     */
    public CompletableFuture<Run> get(String runId) {
        return CompletableFuture.supplyAsync(() -> checked(() -> sync.get(runId)), executor);
    }

    /**
     * Cancel a run that has not finished yet.
     *
     * @param runId identifier of the run to cancel
     * @return a future of the updated {@link Run} reflecting the cancellation.
     *     It completes exceptionally with a {@code CompletionException}
     *     wrapping the underlying {@code ApiException} on failure
     */
    public CompletableFuture<Run> cancel(String runId) {
        return CompletableFuture.supplyAsync(() -> checked(() -> sync.cancel(runId)), executor);
    }

    /**
     * Start a new run that repeats a previous one.
     *
     * @param runId identifier of the run to repeat
     * @return a future of the new {@link Run}, with {@code rerunOf} set to
     *     {@code runId}. It completes exceptionally with a
     *     {@code CompletionException} wrapping the underlying
     *     {@code ApiException} on failure
     */
    public CompletableFuture<Run> rerun(String runId) {
        return CompletableFuture.supplyAsync(() -> checked(() -> sync.rerun(runId)), executor);
    }

    @FunctionalInterface
    private interface RunsCall<T> {
        T call() throws ApiException;
    }

    private static <T> T checked(RunsCall<T> call) {
        try {
            return call.call();
        } catch (ApiException e) {
            throw new CompletionException(e);
        }
    }
}
