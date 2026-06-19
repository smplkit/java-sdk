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
 * <p>Enablement is per environment: a job runs in an environment only when
 * {@link #environments} holds an entry for it with {@code enabled=true}
 * (scheduled there for a recurring job, triggerable there for a manual one),
 * set via {@link #setEnabled(boolean, String)}. The base
 * {@link #enabled} field is a read-only, derived roll-up (true when enabled in
 * at least one environment) computed from {@link #environments}; it is never
 * sent on the wire.</p>
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
     * least one environment. Computed from {@link #environments} on parse and
     * not sent on the wire. Mutating this field has no effect on the server —
     * set enablement per environment via {@link #setEnabled(boolean, String)} /
     * {@link #environments}.
     */
    public boolean enabled = false;
    /**
     * Per-environment overrides keyed by environment key (e.g.
     * {@code "production"}, {@code "development"}). A recurring job fires in an
     * environment only when {@code environments.get(env).enabled} is
     * {@code true}. Each entry may carry an optional {@link HttpConfig}
     * override; omit it to inherit the base {@link #configuration}. Every
     * referenced environment must exist and be managed for the account.
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
     * 5-field cron expression evaluated in UTC for a recurring job, or an
     * ISO-8601 datetime / the literal {@code "now"} for a one-off run. A
     * datetime or {@code "now"} job disables itself after it fires.
     */
    public String schedule;
    /** The HTTP request to perform when the job fires. */
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

    /**
     * Creation-time only: the environment a one-off job is born in, sent as the
     * {@code X-Smplkit-Environment} header by {@code JobsClient.create}. Ignored
     * for recurring and manual jobs, whose environments come from
     * {@link #environments}.
     */
    String birthEnvironment;

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
    // Per-environment enablement / configuration
    // ------------------------------------------------------------------

    /**
     * Return the override for {@code environment}, creating an empty one if
     * absent so an existing override's other field is preserved when only one
     * of {@code enabled} / {@code configuration} is being set.
     */
    private JobEnvironment environmentOverride(String environment) {
        JobEnvironment env = environments.get(environment);
        if (env == null) {
            env = new JobEnvironment();
            environments.put(environment, env);
        }
        return env;
    }

    /**
     * Enable or disable the job in a single environment, in memory. Call
     * {@link #save()} to persist.
     *
     * @param enabled whether the job fires in {@code environment}
     * @param environment the environment key to set enablement for
     */
    public void setEnabled(boolean enabled, String environment) {
        environmentOverride(environment).enabled = enabled;
    }

    /**
     * Whether the job is enabled in at least one environment (the derived
     * roll-up). Computed live from {@link #environments}, so it reflects any
     * in-memory enablement changes made via {@link #setEnabled(boolean, String)}.
     *
     * @return {@code true} when the job is enabled in any environment
     */
    public boolean isEnabled() {
        return computeEnabledRollup();
    }

    /**
     * Compute the enabled roll-up from {@link #environments}: {@code true} when
     * any environment override has {@code enabled=true}.
     */
    boolean computeEnabledRollup() {
        if (environments == null) {
            return false;
        }
        for (JobEnvironment env : environments.values()) {
            if (env != null && env.enabled) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the job is enabled in a specific environment.
     *
     * @param environment the environment key to test
     * @return {@code true} when the job has an override for {@code environment}
     *     with {@code enabled=true}
     */
    public boolean isEnabled(String environment) {
        JobEnvironment env = environments.get(environment);
        return env != null && env.enabled;
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
     * Set the job's base configuration in memory. Call {@link #save()} to
     * persist.
     *
     * @param configuration the base HTTP request the job sends when it fires
     */
    public void setConfiguration(HttpConfig configuration) {
        this.configuration = configuration;
    }

    /**
     * Set the job's configuration in memory — base ({@code environment == null})
     * or per-environment override. A per-environment override fully replaces the
     * base configuration for that environment. Call {@link #save()} to persist.
     *
     * @param configuration the HTTP request to set
     * @param environment the environment key to set the override for, or
     *     {@code null} to set the base configuration
     */
    public void setConfiguration(HttpConfig configuration, String environment) {
        if (environment == null) {
            this.configuration = configuration;
        } else {
            environmentOverride(environment).configuration = configuration;
        }
    }

    /**
     * The job's base configuration.
     *
     * @return the base HTTP request the job sends when it fires
     */
    public HttpConfig getConfiguration() {
        return configuration;
    }

    /**
     * The job's effective configuration for an environment — that environment's
     * override when it has one, else the base configuration (the request the job
     * actually sends when it fires there).
     *
     * @param environment the environment key, or {@code null} for the base
     *     configuration
     * @return the resolved {@link HttpConfig}
     */
    public HttpConfig getConfiguration(String environment) {
        if (environment != null) {
            JobEnvironment env = environments.get(environment);
            if (env != null && env.configuration != null) {
                return env.configuration;
            }
        }
        return configuration;
    }

    /**
     * Set the job's base schedule in memory. Call {@link #save()} to persist.
     *
     * <p>The base schedule applies to every environment that does not carry its
     * own override; use {@link #setSchedule(String, String)} to vary the cadence
     * for a single environment.</p>
     *
     * @param schedule the new cron / datetime / {@code "now"} schedule
     */
    public void setSchedule(String schedule) {
        this.schedule = schedule;
    }

    /**
     * Set the job's schedule in memory — base ({@code environment == null}) or a
     * per-environment cron override. A per-environment override varies the
     * cadence for that environment only; pass {@code null} as the {@code schedule}
     * for that environment to clear the override and inherit the base schedule.
     * Per-environment overrides are only meaningful on a recurring (cron) job.
     * Call {@link #save()} to persist.
     *
     * @param schedule the schedule to set, or {@code null} to clear a
     *     per-environment override
     * @param environment the environment key to set the override for, or
     *     {@code null} to set the base schedule
     */
    public void setSchedule(String schedule, String environment) {
        if (environment == null) {
            this.schedule = schedule;
        } else {
            environmentOverride(environment).schedule = schedule;
        }
    }

    /**
     * The job's effective schedule for an environment — that environment's cron
     * override when it has one, else the base {@link #schedule} (the cadence the
     * job actually fires on there).
     *
     * @param environment the environment key, or {@code null} for the base
     *     schedule
     * @return the resolved schedule string
     */
    public String getSchedule(String environment) {
        if (environment != null) {
            JobEnvironment env = environments.get(environment);
            if (env != null && env.schedule != null) {
                return env.schedule;
            }
        }
        return schedule;
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
        return listRuns(null, null, null);
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
        return listRuns(environment, null, null);
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
        if (client == null) {
            throw new IllegalStateException("Job was constructed without a client; cannot list runs");
        }
        ListRunsInput input = new ListRunsInput();
        input.job = id;
        if (environment != null) {
            input.environments = List.of(environment);
        }
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
