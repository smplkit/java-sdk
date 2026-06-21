package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * A named, reusable retry policy (active record). Mutate fields, then call
 * {@link #save}.
 *
 * <p>A retry policy describes how failed runs are retried — how many times,
 * how long to wait between attempts, and which failures to retry. Reference it
 * from a job's {@code retryPolicy} (see
 * {@link JobsClient#newRecurringJob(String, String, String, HttpConfig, String, java.util.Map, String, String, String)}
 * and {@link Job#setRetryPolicy(RetryPolicy, String)}). Retry policies are
 * account-global — never environment-scoped.</p>
 */
public final class RetryPolicy {

    private RetryPoliciesClient client;

    /** Caller-supplied unique identifier for the policy (the resource {@code id}), immutable. */
    public String id;
    /** Human-readable name for the policy. */
    public String name;
    /**
     * How many times a failed run is retried after the initial attempt —
     * {@code 3} means up to 4 attempts total. {@code 0} disables retries.
     * Maximum 10.
     */
    public int maxRetries;
    /** How the wait between retries grows (see {@link Backoff}). */
    public Backoff backoff;
    /**
     * The wait before a retry, in seconds — the constant wait for {@code FIXED}
     * backoff, or the base that doubles each retry for {@code EXPONENTIAL}.
     */
    public int delaySeconds;
    /**
     * Ceiling on the wait between retries, for {@code EXPONENTIAL} backoff only.
     * {@code null} (omitted on the wire) leaves it uncapped; omit it for
     * {@code FIXED} backoff.
     */
    public Integer maxDelaySeconds;
    /**
     * Retry a run that timed out. Defaults to {@code false}. Each retry field
     * is independently neutral, so a policy retries exactly the failures you opt
     * into; all-off retries nothing.
     */
    public boolean retryOnTimeout = false;
    /**
     * Retry a run whose destination could not be reached (connection error).
     * Defaults to {@code false}.
     */
    public boolean retryOnConnectionError = false;
    /**
     * Allowlist of response-status patterns to retry on a non-success response.
     * Each element is an exact 3-digit code like {@code "429"} or a class token
     * like {@code "5xx"} (one of {@code "1xx"}–{@code "5xx"}). Defaults to empty
     * (matches no status).
     */
    public List<String> retryStatuses = new ArrayList<>();
    /**
     * Patterns subtracted from {@link #retryStatuses} (same form: exact codes or
     * class tokens). {@code except} wins on overlap. Defaults to empty.
     */
    public List<String> retryStatusesExcept = new ArrayList<>();
    /** When the policy was created. {@code null} for an unsaved instance. */
    public OffsetDateTime createdAt;
    /** When the policy was last modified. */
    public OffsetDateTime updatedAt;
    /** When the policy was deleted; {@code null} for live policies. */
    public OffsetDateTime deletedAt;
    /** Monotonic version counter; bumped on every server-side write. */
    public Integer version;

    RetryPolicy(RetryPoliciesClient client, String id, String name, int maxRetries,
                Backoff backoff, int delaySeconds) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.maxRetries = maxRetries;
        this.backoff = backoff;
        this.delaySeconds = delaySeconds;
    }

    /** Create this policy, or full-replace it if it already exists. */
    public void save() throws ApiException {
        if (client == null) {
            throw new IllegalStateException("RetryPolicy was constructed without a client; cannot save");
        }
        RetryPolicy other = (createdAt == null) ? client.create(this) : client.update(this);
        apply(other);
    }

    /**
     * Async variant of {@link #save()}, scheduled on the common pool.
     *
     * @return a future that completes when the save finishes, or completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<Void> saveAsync() {
        return saveAsync(ForkJoinPool.commonPool());
    }

    /**
     * Async variant of {@link #save()} with a custom executor.
     *
     * @param executor the executor that runs the blocking save
     * @return a future that completes when the save finishes, or completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<Void> saveAsync(Executor executor) {
        return CompletableFuture.runAsync(() -> {
            try {
                save();
            } catch (ApiException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    /** Delete this policy. */
    public void delete() throws ApiException {
        if (client == null || id == null) {
            throw new IllegalStateException("RetryPolicy was constructed without a client or id; cannot delete");
        }
        client.delete(id);
    }

    /**
     * Async variant of {@link #delete()}, scheduled on the common pool.
     *
     * @return a future that completes when the delete finishes, or completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<Void> deleteAsync() {
        return deleteAsync(ForkJoinPool.commonPool());
    }

    /**
     * Async variant of {@link #delete()} with a custom executor.
     *
     * @param executor the executor that runs the blocking delete
     * @return a future that completes when the delete finishes, or completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<Void> deleteAsync(Executor executor) {
        return CompletableFuture.runAsync(() -> {
            try {
                delete();
            } catch (ApiException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    void apply(RetryPolicy other) {
        this.id = other.id;
        this.name = other.name;
        this.maxRetries = other.maxRetries;
        this.backoff = other.backoff;
        this.delaySeconds = other.delaySeconds;
        this.maxDelaySeconds = other.maxDelaySeconds;
        this.retryOnTimeout = other.retryOnTimeout;
        this.retryOnConnectionError = other.retryOnConnectionError;
        this.retryStatuses = other.retryStatuses;
        this.retryStatusesExcept = other.retryStatusesExcept;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
        this.deletedAt = other.deletedAt;
        this.version = other.version;
    }

    @Override
    public String toString() {
        return "RetryPolicy(id=" + id + ", name=" + name + ", max_retries=" + maxRetries + ")";
    }
}
