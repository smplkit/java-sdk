package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * A job definition (sync). Mutate fields, then call {@link #save}.
 *
 * <p>Set base fields directly ({@link #schedule}, {@link #timezone},
 * {@link #configuration}, …). A job runs per environment: reach a
 * {@link JobEnvironment} via {@link #environment(String)} and set its
 * {@code enabled} flag (and any leaf overrides) to run — and vary the request —
 * in that environment (ADR-056). The base definition is disabled everywhere;
 * {@link #enabled} is a read-only, derived roll-up (true when enabled in at
 * least one environment) and is never sent on the wire.</p>
 */
public final class Job {

    private JobsClient client;

    /** Caller-supplied unique identifier for the job (the resource {@code id}). */
    public String id;
    /** Human-readable name for the job. */
    public String name;
    /** Free-text description. {@code null} when unset. */
    public String description;
    /**
     * Read-only, derived roll-up: {@code true} when the job is enabled in at
     * least one environment. Computed from {@link #environments} on parse/save
     * and not sent on the wire. Mutating this field has no effect on the
     * server — enable per environment via
     * {@code job.environment(env).enabled = true}.
     */
    public boolean enabled = false;
    /**
     * Per-environment sparse overrides keyed by environment key (e.g.
     * {@code "production"}, {@code "development"}). Reach one via
     * {@link #environment(String)} and set its {@code enabled} flag / leaf
     * overrides to make the job run (and vary its request) in that environment.
     * Each entry also reports its read-only {@link JobEnvironment#nextRunAt}.
     * Every referenced environment must exist and be managed for the account.
     */
    public Map<String, JobEnvironment> environments = new HashMap<>();
    /**
     * Read-only server-derived kind: {@link JobKind#RECURRING} for a cron
     * schedule, {@link JobKind#MANUAL} for no schedule, or
     * {@link JobKind#ONE_OFF} for a {@code "now"} / datetime schedule.
     * {@code null} for an unsaved instance.
     */
    public JobKind kind;
    /** Job type. Only {@code "http"} is supported today. */
    public String type = "http";
    /**
     * The base schedule every environment inherits unless it overrides it, and
     * the field that determines the job's {@link #kind}: {@code null} for a
     * permanent manual job (never auto-fires; runs only when triggered), a
     * 5-field cron expression evaluated in {@link #timezone} for a recurring
     * job, or an ISO-8601 datetime / the literal {@code "now"} for a one-off
     * run. A datetime or {@code "now"} job disables itself after it fires.
     */
    public String schedule;
    /**
     * The base IANA timezone the cron {@link #schedule} is evaluated in (e.g.
     * {@code "America/New_York"}); {@code null} means UTC. The base every
     * environment inherits unless it sets its own {@link JobEnvironment#timezone}.
     * The cron fires on this zone's wall clock (DST-aware) while the per-env
     * {@link JobEnvironment#nextRunAt} is still reported as a UTC instant. Only
     * valid on a recurring (cron) job — leave {@code null} for a manual or
     * one-off job. Sent on writes only when non-{@code null}.
     */
    public String timezone;
    /**
     * The base retry policy for failed runs — the id of a {@link RetryPolicy}
     * (or the built-in {@code "Default"}, which never retries), overridable per
     * environment via {@link JobEnvironment#retryPolicy}. {@code null} (omitted
     * on the wire) leaves the server default of {@code "Default"}. Assign the id
     * directly, or use {@link #setRetryPolicy(RetryPolicy)} to reference a
     * policy instance.
     */
    public String retryPolicy;
    /** The base HTTP request to perform when the job fires. */
    public HttpConfig configuration;
    /** How overlapping runs are handled. {@code "ALLOW"} (the only value) permits them. */
    public String concurrencyPolicy = "ALLOW";
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

    // ------------------------------------------------------------------
    // Per-environment overrides
    // ------------------------------------------------------------------

    /**
     * The per-environment override for {@code environment} — the single place to
     * read or set what this job overrides there (ADR-056).
     *
     * <p>Returns the {@link JobEnvironment} for {@code environment}, creating an
     * empty one (and inserting it into {@link #environments}) on first access,
     * so you can set overrides directly:</p>
     *
     * <pre>{@code
     * job.environment("production").enabled = true;
     * job.environment("production").url = "https://prod.example.com/warm";
     * job.environment("production").setHeader("Authorization", "Bearer prod");
     * }</pre>
     *
     * <p>Only the leaves you set are sent on save; everything else inherits the
     * base definition (the server resolves base &oplus; overrides when the job
     * fires).</p>
     *
     * @param environment the environment key
     * @return the {@link JobEnvironment} override for {@code environment}
     */
    public JobEnvironment environment(String environment) {
        JobEnvironment env = environments.get(environment);
        if (env == null) {
            env = new JobEnvironment();
            environments.put(environment, env);
        }
        return env;
    }

    /**
     * Set the job's base retry policy from a {@link RetryPolicy} instance (its
     * {@link RetryPolicy#id} is used) in memory. Call {@link #save()} to persist.
     *
     * @param policy the retry policy whose id to reference
     */
    public void setRetryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy.id;
    }

    /**
     * Whether this is a recurring (cron-scheduled) job.
     *
     * @return {@code true} when {@link #kind} is {@link JobKind#RECURRING}
     */
    public boolean isRecurring() {
        return kind == JobKind.RECURRING;
    }

    /**
     * Whether this is a manual job — no schedule; runs only when triggered.
     *
     * @return {@code true} when {@link #kind} is {@link JobKind#MANUAL}
     */
    public boolean isManual() {
        return kind == JobKind.MANUAL;
    }

    /**
     * Whether this is a one-off job — a single {@code "now"} / datetime run.
     *
     * @return {@code true} when {@link #kind} is {@link JobKind#ONE_OFF}
     */
    public boolean isOneOff() {
        return kind == JobKind.ONE_OFF;
    }

    /**
     * Compute the enabled roll-up from {@link #environments}: {@code true} when
     * any environment override has {@code enabled=true}.
     */
    boolean computeEnabledRollup() {
        for (JobEnvironment env : environments.values()) {
            if (env != null && env.enabled) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Active-record run helpers
    // ------------------------------------------------------------------

    /**
     * Trigger one immediate, manual run of this job in the client's configured
     * environment (a {@code MANUAL} run).
     *
     * @return the {@link Run} that was started
     * @throws ApiException if the request fails
     */
    public Run trigger() throws ApiException {
        if (client == null) {
            throw new IllegalStateException("Job was constructed without a client; cannot trigger a run");
        }
        return client.run(id);
    }

    /**
     * Trigger one immediate, manual run of this job in a named environment (a
     * {@code MANUAL} run).
     *
     * @param environment the environment the run executes in; {@code null}
     *     falls back to the client's configured environment
     * @return the {@link Run} that was started
     * @throws ApiException if the request fails
     */
    public Run trigger(String environment) throws ApiException {
        if (client == null) {
            throw new IllegalStateException("Job was constructed without a client; cannot trigger a run");
        }
        return client.run(id, environment);
    }

    /**
     * List this job's run history, most recent first, across every environment
     * you can access.
     *
     * @return the runs in this page, as a list of {@link Run}
     * @throws ApiException if the request fails
     */
    public List<Run> listRuns() throws ApiException {
        return listRuns(null, null, false, null, null);
    }

    /**
     * List this job's run history, most recent first, restricted to a single
     * environment.
     *
     * @param environment restrict to runs stamped with this environment;
     *     {@code null} covers every environment you can access
     * @return the runs in this page, as a list of {@link Run}
     * @throws ApiException if the request fails
     */
    public List<Run> listRuns(String environment) throws ApiException {
        return listRuns(environment, null, false, null, null);
    }

    /**
     * List this job's run history, most recent first, restricted to a single
     * environment, optionally collapsed to the last completed run.
     *
     * @param environment restrict to runs stamped with this environment;
     *     {@code null} covers every environment you can access
     * @param lastRunOnly when {@code true}, return only the last completed run
     *     per environment (in-flight runs excluded)
     * @return the runs in this page, as a list of {@link Run}
     * @throws ApiException if the request fails
     */
    public List<Run> listRuns(String environment, boolean lastRunOnly) throws ApiException {
        return listRuns(environment, null, lastRunOnly, null, null);
    }

    /**
     * List this job's run history, most recent first, with an environment filter
     * and cursor paging.
     *
     * @param environment restrict to runs stamped with this environment;
     *     {@code null} covers every environment you can access
     * @param pageSize maximum number of runs to return in this page;
     *     {@code null} uses the server default
     * @param after opaque cursor from a previous page; {@code null} starts from
     *     the first page
     * @return the runs in this page, as a list of {@link Run}
     * @throws ApiException if the request fails
     */
    public List<Run> listRuns(String environment, Integer pageSize, String after) throws ApiException {
        return listRuns(environment, null, false, pageSize, after);
    }

    /**
     * List this job's run history, most recent first, with the full set of
     * filters and cursor paging.
     *
     * @param environment restrict to runs stamped with this environment;
     *     {@code null} covers every environment you can access
     * @param triggers restrict to runs started by any of these triggers (see
     *     {@link RunTrigger}) — e.g. {@code [RunTrigger.RETRY]} for automatic
     *     retries; {@code null}/empty covers every trigger
     * @param lastRunOnly when {@code true}, return only the last completed run
     *     per environment (in-flight runs excluded); the other filters apply
     *     first
     * @param pageSize maximum number of runs to return in this page;
     *     {@code null} uses the server default
     * @param after opaque cursor from a previous page; {@code null} starts from
     *     the first page
     * @return the runs in this page, as a list of {@link Run}
     * @throws ApiException if the request fails
     */
    public List<Run> listRuns(String environment, List<RunTrigger> triggers, boolean lastRunOnly,
                              Integer pageSize, String after) throws ApiException {
        if (client == null) {
            throw new IllegalStateException("Job was constructed without a client; cannot list runs");
        }
        ListRunsInput input = new ListRunsInput();
        input.job = id;
        if (environment != null) {
            input.environments = List.of(environment);
        }
        input.triggers = triggers;
        input.lastRunOnly = lastRunOnly;
        input.pageSize = pageSize;
        input.after = after;
        return client.runs.list(input);
    }

    void apply(Job other) {
        this.id = other.id;
        this.name = other.name;
        this.description = other.description;
        this.environments = other.environments;
        // ``enabled`` is the derived roll-up — recompute from the just-applied
        // environments rather than trusting a stale value.
        this.enabled = computeEnabledRollup();
        this.kind = other.kind;
        this.type = other.type;
        this.schedule = other.schedule;
        this.timezone = other.timezone;
        this.retryPolicy = other.retryPolicy;
        this.configuration = other.configuration;
        this.concurrencyPolicy = other.concurrencyPolicy;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
        this.deletedAt = other.deletedAt;
        this.version = other.version;
    }

    @Override
    public String toString() {
        java.util.List<String> enabledEnvs = new java.util.ArrayList<>();
        for (Map.Entry<String, JobEnvironment> e : environments.entrySet()) {
            if (e.getValue() != null && e.getValue().enabled) {
                enabledEnvs.add(e.getKey());
            }
        }
        java.util.Collections.sort(enabledEnvs);
        return "Job(id=" + id + ", name=" + name + ", enabled_in=" + enabledEnvs + ")";
    }
}
