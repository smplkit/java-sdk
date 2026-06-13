package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * A job definition (sync). Mutate fields, then call {@link #save}.
 */
public final class Job {

    private JobsClient client;

    /** Caller-supplied unique identifier for the job (the resource {@code id}). */
    public String id;
    /** Human-readable name for the job. */
    public String name;
    /** Free-text description. {@code null} when unset. */
    public String description;
    /** Whether the job is scheduling runs. {@code false} pauses without deleting. */
    public boolean enabled = true;
    /** Job type. Only {@code "http"} is supported today. */
    public String type = "http";
    /**
     * When the job runs: an ISO-8601 datetime (a one-off run), a 5-field cron
     * expression evaluated in UTC (recurring), or the literal {@code "now"}
     * (run once, as soon as possible). A datetime or {@code "now"} job
     * disables itself after it fires.
     */
    public String schedule;
    /** The HTTP request to perform when the job fires. */
    public HttpConfig configuration;
    /** How overlapping runs are handled. {@code "ALLOW"} (the only value) permits them. */
    public String concurrencyPolicy = "ALLOW";
    /** The next scheduled fire time. {@code null} once a one-off job has fired. */
    public OffsetDateTime nextRunAt;
    /** When the job was created. {@code null} for an unsaved instance. */
    public OffsetDateTime createdAt;
    /** When the job was last modified. */
    public OffsetDateTime updatedAt;
    /** When the job was deleted; {@code null} for live jobs. */
    public OffsetDateTime deletedAt;
    /** Monotonic version counter; bumped on every server-side write. */
    public Integer version;

    Job(JobsClient client, String id, String name, String schedule, HttpConfig configuration) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.schedule = schedule;
        this.configuration = configuration;
    }

    /** Create this job, or full-replace it if it already exists. */
    public void save() throws ApiException {
        if (client == null) {
            throw new IllegalStateException("Job was constructed without a client; cannot save");
        }
        Job other = (createdAt == null) ? client.create(this) : client.update(this);
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

    /** Delete this job. */
    public void delete() throws ApiException {
        if (client == null || id == null) {
            throw new IllegalStateException("Job was constructed without a client or id; cannot delete");
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

    void apply(Job other) {
        this.id = other.id;
        this.name = other.name;
        this.description = other.description;
        this.enabled = other.enabled;
        this.type = other.type;
        this.schedule = other.schedule;
        this.configuration = other.configuration;
        this.concurrencyPolicy = other.concurrencyPolicy;
        this.nextRunAt = other.nextRunAt;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
        this.deletedAt = other.deletedAt;
        this.version = other.version;
    }

    @Override
    public String toString() {
        return "Job(id=" + id + ", name=" + name + ", enabled=" + enabled + ")";
    }
}
