package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Async wrapper around {@link RetryPoliciesClient}
 * ({@code asyncJobs.retryPolicies}).
 *
 * <p>The {@code new_} constructors are synchronous (no I/O) and return the same
 * {@link RetryPolicy} (bound to the underlying sync client); call
 * {@code policy.saveAsync()} to create it. The read/delete methods return a
 * {@code CompletableFuture}.</p>
 */
public final class AsyncRetryPoliciesClient {

    private final RetryPoliciesClient sync;
    private final Executor executor;

    AsyncRetryPoliciesClient(RetryPoliciesClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /**
     * Return an unsaved {@link RetryPolicy} that retries nothing (all retry
     * conditions off) and has no {@code maxDelaySeconds}. Call {@code .save()} /
     * {@code .saveAsync()} to create it. Synchronous — no I/O.
     *
     * @param id caller-supplied unique identifier for the policy
     * @param name human-readable name for the policy
     * @param maxRetries retries after the initial attempt ({@code 0} disables)
     * @param backoff how the wait between retries grows (see {@link Backoff})
     * @param delaySeconds the wait before a retry, in seconds
     * @return an unsaved {@link RetryPolicy} bound to the underlying sync client
     */
    public RetryPolicy new_(String id, String name, int maxRetries, Backoff backoff, int delaySeconds) {
        return sync.new_(id, name, maxRetries, backoff, delaySeconds);
    }

    /**
     * Return an unsaved {@link RetryPolicy}, setting every field at
     * construction. Call {@code .save()} / {@code .saveAsync()} to create it.
     * Synchronous — no I/O.
     *
     * @param id caller-supplied unique identifier for the policy
     * @param name human-readable name for the policy
     * @param maxRetries retries after the initial attempt ({@code 0} disables)
     * @param backoff how the wait between retries grows (see {@link Backoff})
     * @param delaySeconds the wait before a retry, in seconds
     * @param maxDelaySeconds ceiling on the wait, for {@code EXPONENTIAL} only;
     *     {@code null} leaves it uncapped
     * @param retryOnTimeout retry a run that timed out
     * @param retryOnConnectionError retry a run whose destination could not be
     *     reached
     * @param retryStatuses allowlist of response-status patterns to retry —
     *     each an exact 3-digit code like {@code "429"} or a class token like
     *     {@code "5xx"}; {@code null} starts empty
     * @param retryStatusesExcept patterns subtracted from {@code retryStatuses}
     *     ({@code except} wins on overlap); {@code null} starts empty
     * @return an unsaved {@link RetryPolicy} bound to the underlying sync client
     */
    public RetryPolicy new_(String id, String name, int maxRetries, Backoff backoff, int delaySeconds,
                            Integer maxDelaySeconds, boolean retryOnTimeout, boolean retryOnConnectionError,
                            List<String> retryStatuses, List<String> retryStatusesExcept) {
        return sync.new_(id, name, maxRetries, backoff, delaySeconds, maxDelaySeconds,
                retryOnTimeout, retryOnConnectionError, retryStatuses, retryStatusesExcept);
    }

    /**
     * List retry policies in the account using default paging.
     *
     * @return a future of the policies in the first page. It completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<List<RetryPolicy>> list() {
        return CompletableFuture.supplyAsync(() -> checked(sync::list), executor);
    }

    /**
     * List retry policies in the account.
     *
     * @param input filters and paging for the listing
     * @return a future of the policies in this page. It completes exceptionally
     *     with a {@code CompletionException} wrapping the underlying
     *     {@code ApiException} on failure
     */
    public CompletableFuture<List<RetryPolicy>> list(ListRetryPoliciesInput input) {
        return CompletableFuture.supplyAsync(() -> checked(() -> sync.list(input)), executor);
    }

    /**
     * Fetch a single retry policy by its id.
     *
     * @param id identifier of the policy to fetch
     * @return a future of the matching {@link RetryPolicy}. It completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<RetryPolicy> get(String id) {
        return CompletableFuture.supplyAsync(() -> checked(() -> sync.get(id)), executor);
    }

    /**
     * Delete a retry policy by its id.
     *
     * @param id identifier of the policy to delete
     * @return a future that completes when the delete finishes. It completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> {
            try {
                sync.delete(id);
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @FunctionalInterface
    private interface PolicyCall<T> {
        T call() throws ApiException;
    }

    private static <T> T checked(PolicyCall<T> call) {
        try {
            return call.call();
        } catch (ApiException e) {
            throw new CompletionException(e);
        }
    }
}
