package com.smplkit.jobs;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.jobs.ApiClient;
import com.smplkit.internal.generated.jobs.ApiException;
import com.smplkit.internal.generated.jobs.api.JobsApi;
import com.smplkit.internal.generated.jobs.api.RunsApi;
import com.smplkit.internal.generated.jobs.api.UsageApi;
import com.smplkit.internal.generated.jobs.model.JobCreateRequest;
import com.smplkit.internal.generated.jobs.model.JobCreateResource;
import com.smplkit.internal.generated.jobs.model.JobListResponse;
import com.smplkit.internal.generated.jobs.model.JobRequest;
import com.smplkit.internal.generated.jobs.model.JobResource;
import com.smplkit.internal.generated.jobs.model.JobResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronous Smpl Jobs client.
 *
 * <p>Smpl Jobs runs an HTTP call on a schedule and records what happened each
 * time it fired. A single {@link JobsClient} (and its async counterpart
 * {@link AsyncJobsClient}) exposes the full surface, reachable two ways:</p>
 *
 * <ul>
 *   <li>{@code client.jobs} on {@link com.smplkit.SmplClient}</li>
 *   <li>directly — {@code JobsClient.create(apiKey)} — for callers that only
 *       need jobs.</li>
 * </ul>
 *
 * <p>A {@link Job} is an active record: build it with {@link #new_}, set
 * fields, and call {@code save()} (create when new, full-replace update when it
 * already exists) or {@code delete()}. A {@link Run} is a read-only record of
 * one execution; run history and run actions live on {@code jobs.runs}.</p>
 *
 * <p>Enablement is per environment: a recurring job supplies an
 * {@code environments} map to choose where it runs; a one-off job is born in a
 * single environment. A client-level {@code environment} (see {@link
 * JobsClientBuilder#environment(String)}) defaults the one-off birth
 * environment on create, the environment a manual run executes in, and the
 * {@code filter[environment]} scope on {@code jobs.runs.list()}.</p>
 *
 * <pre>{@code
 * try (JobsClient jobs = JobsClient.create()) {
 *     for (Job job : jobs.list()) {
 *         System.out.println(job.id);
 *     }
 * }
 * }</pre>
 */
public final class JobsClient implements AutoCloseable {

    private final JobsApi api;
    private final UsageApi usageApi;
    private final String environment;
    private final boolean ownsTransport;

    /** Run history and run actions ({@code jobs.runs}). */
    public final RunsClient runs;

    /**
     * Wired constructor (no environment) — invoked by
     * {@link com.smplkit.SmplClient} so the jobs surface shares the parent
     * client's connection pool.
     *
     * @param apiKey       resolved API key
     * @param extraHeaders extra headers attached to every request (never null)
     * @param timeout      per-request read timeout
     * @param baseUrl      fully-qualified jobs service base URL
     */
    public JobsClient(String apiKey, Map<String, String> extraHeaders,
               Duration timeout, String baseUrl) {
        this(apiKey, extraHeaders, timeout, baseUrl, null);
    }

    /**
     * Wired constructor — invoked by {@link com.smplkit.SmplClient} so the jobs
     * surface shares the parent client's connection pool. Builds the generated
     * jobs transport from the parent's resolved credentials. The resulting
     * client does NOT own its transport, so {@link #close()} is a no-op.
     *
     * @param apiKey       resolved API key
     * @param extraHeaders extra headers attached to every request (never null)
     * @param timeout      per-request read timeout
     * @param baseUrl      fully-qualified jobs service base URL
     *                     ({@code ConfigResolver.serviceUrl(scheme, "jobs", baseDomain)})
     * @param environment  the SDK's configured environment (defaults the one-off
     *                     birth header, the run-now header, and the runs read
     *                     filter), or {@code null} to leave them unset
     */
    public JobsClient(String apiKey, Map<String, String> extraHeaders,
               Duration timeout, String baseUrl, String environment) {
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(baseUrl);
        apiClient.setRequestInterceptor(HttpClients.compositeInterceptor(apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);
        this.api = new JobsApi(apiClient);
        this.usageApi = new UsageApi(apiClient);
        this.environment = environment;
        this.runs = new RunsClient(new RunsApi(apiClient), environment);
        this.ownsTransport = false;
    }

    /**
     * Standalone constructor — builds and OWNS its own transport. Used by
     * {@link JobsClientBuilder#build()}.
     */
    private JobsClient(ResolvedClientConfig cfg, String environment, Duration timeout,
                       Map<String, String> extraHeaders) {
        String baseUrl = ConfigResolver.serviceUrl(cfg.scheme, "jobs", cfg.baseDomain);
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(baseUrl);
        apiClient.setRequestInterceptor(HttpClients.compositeInterceptor(cfg.apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);
        this.api = new JobsApi(apiClient);
        this.usageApi = new UsageApi(apiClient);
        this.environment = environment;
        this.runs = new RunsClient(new RunsApi(apiClient), environment);
        this.ownsTransport = true;
    }

    /**
     * Return an unsaved {@link Job} with default {@code description} (none), no
     * per-environment overrides, and {@code concurrencyPolicy} ({@code "ALLOW"}).
     * Call {@code .save()} to create it. A one-off job is born in the client's
     * configured environment; a recurring job sets enablement per environment
     * via {@link Job#setEnabled(boolean, String)}.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param schedule when the job runs. One of: a 5-field cron expression
     *     evaluated in UTC (recurring); an ISO-8601 datetime (a one-off run at
     *     that instant); or the literal {@code "now"} (run once, as soon as
     *     possible). A datetime or {@code "now"} job disables itself after it
     *     fires.
     * @param configuration the HTTP request the job sends each time it fires
     * @return an unsaved {@link Job} bound to this client
     */
    public Job new_(String id, String name, String schedule, HttpConfig configuration) {
        return newJob(id, name, schedule, configuration, null, new HashMap<>(), "ALLOW", null);
    }

    /**
     * Return an unsaved one-off {@link Job} born in a named environment. Call
     * {@code .save()} to create it. Use this for a {@code "now"} / datetime
     * schedule: the job is created in {@code environment} (sent as the
     * {@code X-Smplkit-Environment} header on save). Ignored for a recurring
     * job, whose environments come from {@link Job#environments}.
     *
     * @param id stable caller-supplied unique identifier for the job
     * @param name human-readable name for the job
     * @param schedule when the job runs (see {@link #new_(String, String, String, HttpConfig)})
     * @param configuration the HTTP request the job sends each time it fires
     * @param environment the environment a one-off job is born in; {@code null}
     *     falls back to the client's configured environment
     * @return an unsaved {@link Job} bound to this client
     */
    public Job new_(String id, String name, String schedule, HttpConfig configuration,
                    String environment) {
        return newJob(id, name, schedule, configuration, null, new HashMap<>(), "ALLOW", environment);
    }

    /**
     * Return an unsaved {@link Job}, setting every job field at construction.
     * Call {@code .save()} to create it.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param schedule when the job runs (see {@link #new_(String, String, String, HttpConfig)})
     * @param configuration the HTTP request the job sends each time it fires
     * @param description free-text description for the job; {@code null} for none
     * @param environments per-environment overrides for a recurring job, keyed
     *     by environment key; a recurring job fires only in environments enabled
     *     here. {@code null} starts with no overrides. Ignored for a one-off job.
     * @param concurrencyPolicy how overlapping runs are handled. {@code "ALLOW"}
     *     (the default and only value today) permits a new run to start while a
     *     previous one is still in flight
     * @param environment for a one-off job, the environment it is born in;
     *     {@code null} falls back to the client's configured environment.
     *     Ignored for a recurring job.
     * @return an unsaved {@link Job} bound to this client
     */
    public Job new_(String id, String name, String schedule, HttpConfig configuration,
                    String description, Map<String, JobEnvironment> environments,
                    String concurrencyPolicy, String environment) {
        return newJob(id, name, schedule, configuration, description,
                environments != null ? environments : new HashMap<>(), concurrencyPolicy, environment);
    }

    private Job newJob(String id, String name, String schedule, HttpConfig configuration,
                       String description, Map<String, JobEnvironment> environments,
                       String concurrencyPolicy, String environment) {
        Job job = new Job(this, id, name, schedule, configuration);
        job.description = description;
        job.environments = environments;
        job.concurrencyPolicy = concurrencyPolicy;
        // A one-off job's birth environment: explicit wins, else the client default.
        job.birthEnvironment = environment != null ? environment : this.environment;
        return job;
    }

    /**
     * List jobs in the account using default filters and page size.
     *
     * @return the jobs in the first page, as a list of {@link Job}
     * @throws ApiException if the request fails
     */
    public List<Job> list() throws ApiException {
        return list(new ListJobsInput());
    }

    /**
     * List jobs in the account.
     *
     * @param input filters and paging for the listing. {@code enabled} returns
     *     only jobs with that enabled state ({@code null} lists both);
     *     {@code recurring} returns only recurring ({@code true}) or only
     *     one-off ({@code false}) jobs ({@code null} lists both);
     *     {@code pageNumber} is the 1-based page ({@code null} returns the
     *     first page); {@code pageSize} is the max jobs per page ({@code null}
     *     uses the server default)
     * @return the jobs in this page, as a list of {@link Job}
     * @throws ApiException if the request fails
     */
    public List<Job> list(ListJobsInput input) throws ApiException {
        JobListResponse resp = api.listJobs(
            input.enabled, input.recurring, input.name, null, input.pageNumber, input.pageSize, null);
        List<Job> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (JobResource r : resp.getData()) out.add(fromResource(r));
        }
        return out;
    }

    /**
     * Fetch a single job by its id; the returned instance is bound to this
     * client so {@code job.save()} and {@code job.delete()} round-trip back
     * here.
     *
     * @param jobId identifier of the job to fetch
     * @return the matching {@link Job}
     * @throws com.smplkit.internal.generated.jobs.ApiException if no job with
     *     that id exists, or if the request otherwise fails
     */
    public Job get(String jobId) throws ApiException {
        JobResponse resp = api.getJob(jobId);
        return fromResource(resp.getData());
    }

    /**
     * Delete a job by its id.
     *
     * @param jobId identifier of the job to delete
     * @throws com.smplkit.internal.generated.jobs.ApiException if no job with
     *     that id exists, or if the request otherwise fails
     */
    public void delete(String jobId) throws ApiException {
        api.deleteJob(jobId);
    }

    /**
     * Trigger one immediate, manual run of a job in the client's configured
     * environment, ignoring its schedule.
     *
     * <p>This starts an ad-hoc run right now in addition to any scheduled runs;
     * it does not alter the job's schedule. To read or act on existing runs,
     * use {@code jobs.runs}.</p>
     *
     * @param jobId identifier of the job to run
     * @return the {@link Run} that was started, with {@code trigger} set to
     *     {@code MANUAL}
     * @throws ApiException if the request fails
     */
    public Run run(String jobId) throws ApiException {
        return run(jobId, null);
    }

    /**
     * Trigger one immediate, manual run of a job in a named environment,
     * ignoring its schedule.
     *
     * @param jobId identifier of the job to run
     * @param environment the environment the manual run executes in;
     *     {@code null} falls back to the client's configured environment. When
     *     the job is enabled in exactly one environment that environment is
     *     used, and a single-environment credential implies it.
     * @return the {@link Run} that was started, with {@code trigger} set to
     *     {@code MANUAL}
     * @throws ApiException if the request fails
     */
    public Run run(String jobId, String environment) throws ApiException {
        String env = environment != null ? environment : this.environment;
        return JobsConversions.runFromResource(api.runJobNow(jobId, env).getData(), runs);
    }

    /**
     * Report current-period usage against the account's plan allotments.
     *
     * @return a {@link Usage} snapshot with runs used/included and active-job
     *     counts for the current period
     * @throws ApiException if the request fails
     */
    public Usage usage() throws ApiException {
        return JobsConversions.usageFromResource(usageApi.getUsage(null).getData());
    }

    // ------------------------------------------------------------------
    // Active-record helpers (called by Job.save)
    // ------------------------------------------------------------------

    Job create(Job job) throws ApiException {
        if (job.id == null) {
            throw new IllegalStateException("cannot create a Job with no id (caller must supply a stable key)");
        }
        // A one-off job is born in its birth environment, named on the
        // X-Smplkit-Environment header; ignored for a recurring job.
        JobResponse resp = api.createJob(wrapCreateRequest(job), job.birthEnvironment);
        return fromResource(resp.getData());
    }

    Job update(Job job) throws ApiException {
        if (job.id == null) {
            throw new IllegalStateException("cannot update a Job with no id");
        }
        // Update names the client's configured environment on the header (if any).
        JobResponse resp = api.updateJob(job.id, wrapRequest(job.id, job), environment);
        return fromResource(resp.getData());
    }

    /**
     * Release HTTP resources — only when this client owns its transport.
     *
     * <p>A jobs client wired by a top-level client shares that client's
     * transport and must not close it here; the owning client's
     * {@code close()} handles teardown. A standalone jobs client owns its
     * transport but has no persistent resources to release.</p>
     */
    @Override
    public void close() {
        if (ownsTransport) {
            // No persistent resources beyond the owned HttpClient (managed by JDK).
        }
    }

    // ------------------------------------------------------------------
    // Standalone construction (mirrors SmplClient.create / builder())
    // ------------------------------------------------------------------

    /**
     * Construct a standalone {@link JobsClient}, resolving credentials from the
     * standard sources (env vars, {@code ~/.smplkit}). Owns its own transport.
     */
    public static JobsClient create() {
        return builder().build();
    }

    /** Construct a standalone {@link JobsClient} with the given API key. */
    public static JobsClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** Returns a builder for a standalone {@link JobsClient}. */
    public static JobsClientBuilder builder() {
        return new JobsClientBuilder();
    }

    /** Internal: build a standalone client from already-resolved config. */
    static JobsClient fromResolved(ResolvedClientConfig cfg, String environment, Duration timeout,
                                   Map<String, String> extraHeaders) {
        return new JobsClient(cfg, environment, timeout, extraHeaders);
    }

    // ------------------------------------------------------------------
    // Wire <-> wrapper conversions
    // ------------------------------------------------------------------

    private static com.smplkit.internal.generated.jobs.model.Job genAttrs(com.smplkit.jobs.Job job) {
        com.smplkit.internal.generated.jobs.model.Job attrs =
                new com.smplkit.internal.generated.jobs.model.Job();
        attrs.setName(job.name);
        attrs.setDescription(job.description);
        // The base ``enabled`` is a read-only, server-derived roll-up — never
        // sent. Enablement travels entirely through ``environments``.
        if (job.type != null) {
            attrs.setType(com.smplkit.internal.generated.jobs.model.Job.TypeEnum.fromValue(job.type));
        }
        attrs.setSchedule(job.schedule);
        attrs.setConfiguration(JobsConversions.configurationToGen(job.configuration));
        if (job.concurrencyPolicy != null) {
            attrs.setConcurrencyPolicy(com.smplkit.internal.generated.jobs.model.Job
                    .ConcurrencyPolicyEnum.fromValue(job.concurrencyPolicy));
        }
        if (job.environments != null && !job.environments.isEmpty()) {
            attrs.setEnvironments(JobsConversions.environmentsToGen(job.environments));
        } else {
            // The generated model defaults ``environments`` to an empty map, which
            // would serialize as ``{}``; null it so the key is omitted entirely.
            attrs.setEnvironments(null);
        }
        return attrs;
    }

    private static JobRequest wrapRequest(String id, com.smplkit.jobs.Job job) {
        JobResource r = new JobResource();
        r.setId(id);
        r.setType("job");
        r.setAttributes(genAttrs(job));
        JobRequest body = new JobRequest();
        body.setData(r);
        return body;
    }

    private static JobCreateRequest wrapCreateRequest(com.smplkit.jobs.Job job) {
        // Create uses a dedicated envelope where the caller-supplied id is required.
        JobCreateResource r = new JobCreateResource();
        r.setId(job.id);
        r.setType(JobCreateResource.TypeEnum.JOB);
        r.setAttributes(genAttrs(job));
        JobCreateRequest body = new JobCreateRequest();
        body.setData(r);
        return body;
    }

    private com.smplkit.jobs.Job fromResource(JobResource r) {
        com.smplkit.internal.generated.jobs.model.Job a = r.getAttributes();
        com.smplkit.jobs.Job job = new com.smplkit.jobs.Job(
                this, r.getId(), a.getName(), a.getSchedule(),
                JobsConversions.configurationFromGen(a.getConfiguration()));
        job.description = a.getDescription();
        // ``enabled`` is the read-only roll-up; default false when the server
        // omits it (no environment enabled).
        job.enabled = a.getEnabled() != null ? a.getEnabled() : false;
        job.environments = JobsConversions.environmentsFromGen(a.getEnvironments());
        job.recurring = a.getRecurring();
        if (a.getType() != null) job.type = a.getType().getValue();
        if (a.getConcurrencyPolicy() != null) job.concurrencyPolicy = a.getConcurrencyPolicy().getValue();
        job.nextRunAt = a.getNextRunAt();
        job.createdAt = a.getCreatedAt();
        job.updatedAt = a.getUpdatedAt();
        job.deletedAt = a.getDeletedAt();
        job.version = a.getVersion();
        return job;
    }
}
