package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Asynchronous Smpl Jobs client (async counterpart of {@link JobsClient}).
 *
 * <p>Holds a sync {@link JobsClient} as the single source of truth for state.
 * Each I/O method returns a {@code CompletableFuture<T>} scheduled on the
 * configured {@link Executor}; the job constructors ({@link #newRecurringJob},
 * {@link #newManualJob}, {@link #schedule}) are synchronous (no I/O). Run
 * actions live on the async {@link #runs} surface.</p>
 *
 * <p>Use {@link #create()} for default credentials and the common-pool
 * executor; use {@link #wrap(JobsClient, Executor)} to override the executor
 * (recommended for production: a bounded I/O thread pool).</p>
 *
 * <pre>{@code
 * try (AsyncJobsClient jobs = AsyncJobsClient.create()) {
 *     jobs.list().thenAccept(js -> js.forEach(j -> System.out.println(j.id)));
 * }
 * }</pre>
 */
public final class AsyncJobsClient implements AutoCloseable {

    private final JobsClient delegate;
    private final Executor executor;

    /** Async run history and run actions ({@code asyncJobs.runs}). */
    public final AsyncRunsClient runs;

    /** Async reusable retry policies ({@code asyncJobs.retryPolicies}). */
    public final AsyncRetryPoliciesClient retryPolicies;

    private AsyncJobsClient(JobsClient delegate, Executor executor) {
        this.delegate = delegate;
        this.executor = executor;
        this.runs = new AsyncRunsClient(delegate.runs, executor);
        this.retryPolicies = new AsyncRetryPoliciesClient(delegate.retryPolicies, executor);
    }

    /** Create with default credentials and the common-pool executor. */
    public static AsyncJobsClient create() {
        return wrap(JobsClient.create(), ForkJoinPool.commonPool());
    }

    /** Create with the given API key and the common-pool executor. */
    public static AsyncJobsClient create(String apiKey) {
        return wrap(JobsClient.create(apiKey), ForkJoinPool.commonPool());
    }

    /** Wrap an existing {@link JobsClient}, using the common-pool executor. */
    public static AsyncJobsClient wrap(JobsClient delegate) {
        return wrap(delegate, ForkJoinPool.commonPool());
    }

    /**
     * Wrap an existing {@link JobsClient} with a custom executor.
     *
     * <p>For production use, supply a bounded I/O thread pool rather than the
     * common pool — jobs calls are blocking I/O and shouldn't compete with
     * compute work on the common pool.</p>
     */
    public static AsyncJobsClient wrap(JobsClient delegate, Executor executor) {
        return new AsyncJobsClient(delegate, executor);
    }

    /**
     * Returns the underlying sync client.
     *
     * @return the {@link JobsClient} this async client delegates to
     */
    public JobsClient sync() { return delegate; }

    /**
     * Returns the executor used to schedule async work.
     *
     * @return the {@link Executor} that runs the blocking calls
     */
    public Executor executor() { return executor; }

    /**
     * Return an unsaved recurring {@link Job} with default {@code description}
     * (none), no per-environment overrides, and {@code concurrencyPolicy}
     * ({@code "ALLOW"}). Call {@code .save()} / {@code .saveAsync()} to create
     * it. Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param schedule the base cadence — a 5-field cron expression evaluated in
     *     UTC (e.g. {@code "0 2 * * *"}) — that every environment inherits
     *     unless it sets its own override
     * @param configuration the HTTP request the job sends each time it fires
     * @return an unsaved recurring {@link Job} bound to the underlying sync client
     */
    public Job newRecurringJob(String id, String name, String schedule, HttpConfig configuration) {
        return delegate.newRecurringJob(id, name, schedule, configuration);
    }

    /**
     * Return an unsaved recurring {@link Job}, setting every job field at
     * construction. Call {@code .save()} / {@code .saveAsync()} to create it.
     * Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param schedule the base cron cadence (see
     *     {@link #newRecurringJob(String, String, String, HttpConfig)})
     * @param configuration the HTTP request the job sends each time it fires
     * @param description free-text description for the job; {@code null} for none
     * @param environments per-environment overrides keyed by environment key;
     *     {@code null} starts with no overrides
     * @param concurrencyPolicy how overlapping runs are handled. {@code "ALLOW"}
     *     (the default and only value today) permits a new run to start while a
     *     previous one is still in flight
     * @return an unsaved recurring {@link Job} bound to the underlying sync client
     */
    public Job newRecurringJob(String id, String name, String schedule, HttpConfig configuration,
                               String description, Map<String, JobEnvironment> environments,
                               String concurrencyPolicy) {
        return delegate.newRecurringJob(id, name, schedule, configuration, description, environments,
                concurrencyPolicy);
    }

    /**
     * Return an unsaved recurring {@link Job}, setting every job field at
     * construction including the base {@code timezone} and {@code retryPolicy}.
     * Call {@code .save()} / {@code .saveAsync()} to create it. Synchronous —
     * no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param schedule the base cron cadence (see
     *     {@link #newRecurringJob(String, String, String, HttpConfig)})
     * @param configuration the HTTP request the job sends each time it fires
     * @param description free-text description for the job; {@code null} for none
     * @param environments per-environment overrides keyed by environment key;
     *     {@code null} starts with no overrides
     * @param concurrencyPolicy how overlapping runs are handled. {@code "ALLOW"}
     *     (the default and only value today) permits a new run to start while a
     *     previous one is still in flight
     * @param timezone the base IANA timezone the cron is evaluated in;
     *     {@code null} means UTC
     * @param retryPolicy the base retry policy id for failed runs;
     *     {@code null} uses the built-in {@code "Default"} policy
     * @return an unsaved recurring {@link Job} bound to the underlying sync client
     */
    public Job newRecurringJob(String id, String name, String schedule, HttpConfig configuration,
                               String description, Map<String, JobEnvironment> environments,
                               String concurrencyPolicy, String timezone, String retryPolicy) {
        return delegate.newRecurringJob(id, name, schedule, configuration, description, environments,
                concurrencyPolicy, timezone, retryPolicy);
    }

    /**
     * Return an unsaved manual {@link Job} with default {@code description}
     * (none), no per-environment overrides, and {@code concurrencyPolicy}
     * ({@code "ALLOW"}). Call {@code .save()} / {@code .saveAsync()} to create
     * it. A manual job has no schedule — it never auto-fires and runs only when
     * triggered. Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param configuration the HTTP request the job sends each time it runs
     * @return an unsaved manual {@link Job} bound to the underlying sync client
     */
    public Job newManualJob(String id, String name, HttpConfig configuration) {
        return delegate.newManualJob(id, name, configuration);
    }

    /**
     * Return an unsaved manual {@link Job}, setting every job field at
     * construction. Call {@code .save()} / {@code .saveAsync()} to create it. A
     * manual job has no schedule — it never auto-fires and runs only when
     * triggered. Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param configuration the HTTP request the job sends each time it runs
     * @param description free-text description for the job; {@code null} for none
     * @param environments per-environment overrides keyed by environment key;
     *     {@code null} starts with no overrides
     * @param concurrencyPolicy how overlapping runs are handled. {@code "ALLOW"}
     *     (the default and only value today) permits a new run to start while a
     *     previous one is still in flight
     * @return an unsaved manual {@link Job} bound to the underlying sync client
     */
    public Job newManualJob(String id, String name, HttpConfig configuration,
                            String description, Map<String, JobEnvironment> environments,
                            String concurrencyPolicy) {
        return delegate.newManualJob(id, name, configuration, description, environments,
                concurrencyPolicy);
    }

    /**
     * Return an unsaved manual {@link Job}, setting every job field at
     * construction including the {@code retryPolicy}. Call {@code .save()} /
     * {@code .saveAsync()} to create it. A manual job has no schedule (and no
     * timezone). Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param configuration the HTTP request the job sends each time it runs
     * @param description free-text description for the job; {@code null} for none
     * @param environments per-environment overrides keyed by environment key;
     *     {@code null} starts with no overrides
     * @param concurrencyPolicy how overlapping runs are handled. {@code "ALLOW"}
     *     (the default and only value today) permits a new run to start while a
     *     previous one is still in flight
     * @param retryPolicy the retry policy id for failed runs; {@code null} uses
     *     the built-in {@code "Default"} policy
     * @return an unsaved manual {@link Job} bound to the underlying sync client
     */
    public Job newManualJob(String id, String name, HttpConfig configuration,
                            String description, Map<String, JobEnvironment> environments,
                            String concurrencyPolicy, String retryPolicy) {
        return delegate.newManualJob(id, name, configuration, description, environments,
                concurrencyPolicy, retryPolicy);
    }

    /**
     * Return an unsaved one-off {@link Job} born in the client's configured
     * environment. Call {@code .save()} / {@code .saveAsync()} to create it. A
     * one-off job runs a single time at {@code schedule} and is then spent.
     * Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param schedule the instant the single run fires
     * @param configuration the HTTP request the job sends when it runs
     * @return an unsaved one-off {@link Job} bound to the underlying sync client
     */
    public Job schedule(String id, String name, OffsetDateTime schedule, HttpConfig configuration) {
        return delegate.schedule(id, name, schedule, configuration);
    }

    /**
     * Return an unsaved one-off {@link Job} born in a named environment. Call
     * {@code .save()} / {@code .saveAsync()} to create it. Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job
     * @param name human-readable name for the job
     * @param schedule the instant the single run fires
     * @param configuration the HTTP request the job sends when it runs
     * @param environment the environment the job is born in; {@code null} falls
     *     back to the client's configured environment
     * @return an unsaved one-off {@link Job} bound to the underlying sync client
     */
    public Job schedule(String id, String name, OffsetDateTime schedule, HttpConfig configuration,
                        String environment) {
        return delegate.schedule(id, name, schedule, configuration, environment);
    }

    /**
     * Return an unsaved one-off {@link Job}, setting every job field at
     * construction. Call {@code .save()} / {@code .saveAsync()} to create it. A
     * one-off job runs a single time at {@code schedule} and is then spent.
     * Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param schedule the instant the single run fires
     * @param configuration the HTTP request the job sends when it runs
     * @param description free-text description for the job; {@code null} for none
     * @param concurrencyPolicy how overlapping runs are handled. {@code "ALLOW"}
     *     (the default and only value today) permits a new run to start while a
     *     previous one is still in flight
     * @param environment the environment the job is born in; {@code null} falls
     *     back to the client's configured environment
     * @return an unsaved one-off {@link Job} bound to the underlying sync client
     */
    public Job schedule(String id, String name, OffsetDateTime schedule, HttpConfig configuration,
                        String description, String concurrencyPolicy, String environment) {
        return delegate.schedule(id, name, schedule, configuration, description, concurrencyPolicy,
                environment);
    }

    /**
     * Return an unsaved one-off {@link Job}, setting every job field at
     * construction including the {@code retryPolicy}. Call {@code .save()} /
     * {@code .saveAsync()} to create it. Synchronous — no I/O.
     *
     * @param id stable caller-supplied unique identifier for the job
     * @param name human-readable name for the job
     * @param schedule the instant the single run fires
     * @param configuration the HTTP request the job sends when it runs
     * @param description free-text description for the job; {@code null} for none
     * @param concurrencyPolicy how overlapping runs are handled. {@code "ALLOW"}
     *     (the default and only value today) permits a new run to start while a
     *     previous one is still in flight
     * @param retryPolicy the retry policy id for failed runs; {@code null} uses
     *     the built-in {@code "Default"} policy
     * @param environment the environment the job is born in; {@code null} falls
     *     back to the client's configured environment
     * @return an unsaved one-off {@link Job} bound to the underlying sync client
     */
    public Job schedule(String id, String name, OffsetDateTime schedule, HttpConfig configuration,
                        String description, String concurrencyPolicy, String retryPolicy,
                        String environment) {
        return delegate.schedule(id, name, schedule, configuration, description, concurrencyPolicy,
                retryPolicy, environment);
    }

    /**
     * List jobs in the account using default filters and page size.
     *
     * @return a future of the jobs in the first page. It completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} on failure
     */
    public CompletableFuture<List<Job>> list() {
        return CompletableFuture.supplyAsync(() -> checked(delegate::list), executor);
    }

    /**
     * List jobs in the account.
     *
     * @param input filters and paging for the listing. {@code kind} returns
     *     only jobs of that {@link JobKind} ({@code null} lists recurring and
     *     manual jobs; one-off jobs are omitted unless {@link JobKind#ONE_OFF}
     *     is passed); {@code scheduled} returns only jobs with an upcoming fire
     *     in some environment ({@code true}) or none ({@code false});
     *     {@code pageNumber} is the 1-based page ({@code null} returns the
     *     first page); {@code pageSize} is the max jobs per page ({@code null}
     *     uses the server default)
     * @return a future of the jobs in this page. It completes exceptionally
     *     with a {@code CompletionException} wrapping the underlying
     *     {@code ApiException} on failure
     */
    public CompletableFuture<List<Job>> list(ListJobsInput input) {
        return CompletableFuture.supplyAsync(() -> checked(() -> delegate.list(input)), executor);
    }

    /**
     * Fetch a single job by its id.
     *
     * @param id identifier of the job to fetch
     * @return a future of the matching {@link Job}. It completes exceptionally
     *     with a {@code CompletionException} wrapping the underlying
     *     {@code ApiException} (e.g. when no job with that id exists)
     */
    public CompletableFuture<Job> get(String id) {
        return CompletableFuture.supplyAsync(() -> checked(() -> delegate.get(id)), executor);
    }

    /**
     * Delete a job by its id.
     *
     * @param id identifier of the job to delete
     * @return a future that completes when the delete finishes. It completes
     *     exceptionally with a {@code CompletionException} wrapping the
     *     underlying {@code ApiException} (e.g. when no job with that id
     *     exists)
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> {
            try {
                delegate.delete(id);
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Trigger one immediate, manual run of a job, ignoring its schedule.
     *
     * <p>This starts an ad-hoc run right now in addition to any scheduled runs;
     * it does not alter the job's schedule. To read or act on existing runs,
     * use {@code runs}.</p>
     *
     * @param id identifier of the job to run
     * @return a future of the {@link Run} that was started, with
     *     {@code trigger} set to {@code MANUAL}. It completes exceptionally
     *     with a {@code CompletionException} wrapping the underlying
     *     {@code ApiException} on failure
     */
    public CompletableFuture<Run> run(String id) {
        return CompletableFuture.supplyAsync(() -> checked(() -> delegate.run(id)), executor);
    }

    /**
     * Trigger one immediate, manual run of a job in a named environment,
     * ignoring its schedule.
     *
     * @param id identifier of the job to run
     * @param environment the environment the manual run executes in;
     *     {@code null} falls back to the client's configured environment
     * @return a future of the {@link Run} that was started, with
     *     {@code trigger} set to {@code MANUAL}. It completes exceptionally
     *     with a {@code CompletionException} wrapping the underlying
     *     {@code ApiException} on failure
     */
    public CompletableFuture<Run> run(String id, String environment) {
        return CompletableFuture.supplyAsync(() -> checked(() -> delegate.run(id, environment)), executor);
    }

    /**
     * Report current-period usage against the account's plan allotments.
     *
     * @return a future of a {@link Usage} snapshot with runs used/included and
     *     active-job counts for the current period. It completes exceptionally
     *     with a {@code CompletionException} wrapping the underlying
     *     {@code ApiException} on failure
     */
    public CompletableFuture<Usage> usage() {
        return CompletableFuture.supplyAsync(() -> checked(delegate::usage), executor);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @FunctionalInterface
    private interface JobsCall<T> {
        T call() throws ApiException;
    }

    private static <T> T checked(JobsCall<T> call) {
        try {
            return call.call();
        } catch (ApiException e) {
            throw new CompletionException(e);
        }
    }
}
