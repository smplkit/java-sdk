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
 * <p>Enablement is per environment: a recurring (cron) job fires in an
 * environment only when {@link #environments} holds an entry for it with
 * {@code enabled=true}, set via {@link #setEnabled(boolean, String)}. The base
 * {@link #enabled} field is a read-only, server-derived roll-up (true when
 * enabled in at least one environment) and is never written back.</p>
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
     * Read-only, server-derived roll-up: {@code true} when the job is enabled
     * in at least one environment. Mutating this field has no effect on the
     * server — set enablement per environment via
     * {@link #setEnabled(boolean, String)} / {@link #environments}.
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
     * Read-only: {@code true} for a recurring (cron) schedule, {@code false}
     * for a one-off ({@code "now"} / datetime) schedule. {@code null} for an
     * unsaved instance.
     */
    public Boolean recurring;
    /** Job type. Only {@code "http"} is supported today. */
    public String type = "http";
    /**
     * When the job runs: an ISO-8601 datetime (a one-off run), a 5-field cron
     * expression evaluated in UTC (recurring), or the literal {@code "now"}
     * (run once, as soon as possible). A datetime or {@code "now"} job
     * disables itself after it fires. The schedule is environment-agnostic —
     * one cadence shared across every environment the job runs in.
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

    /**
     * Creation-time only: the environment a one-off job is born in, sent as the
     * {@code X-Smplkit-Environment} header by {@code JobsClient.create}. Ignored
     * for a recurring job, whose environments come from {@link #environments}.
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
     * Whether the job is enabled in at least one environment (the read-only
     * roll-up).
     *
     * @return {@code true} when the job is enabled in any environment
     */
    public boolean isEnabled() {
        return enabled;
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
     * Set the job's schedule in memory. Call {@link #save()} to persist.
     *
     * <p>The schedule is environment-agnostic — a job has a single cron /
     * datetime / {@code "now"} schedule shared across every environment it runs
     * in, so this setter takes no environment.</p>
     *
     * @param schedule the new cron / datetime / {@code "now"} schedule
     */
    public void setSchedule(String schedule) {
        this.schedule = schedule;
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
        this.enabled = other.enabled;
        this.environments = other.environments;
        this.recurring = other.recurring;
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
