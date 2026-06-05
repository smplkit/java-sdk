package com.smplkit.jobs;

import com.smplkit.SmplClient;
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
import java.util.List;
import java.util.Map;

/**
 * Smpl Jobs management surface — accessed via {@code SmplManagementClient.jobs}.
 *
 * <p>Unlike Config/Flags/Logging, Jobs has no live "phone-home" agent — no
 * environment registration, no WebSocket — so its entire surface lives on the
 * management client. Defining a job, triggering a run, and reading run history
 * are all plain request/response calls here:</p>
 *
 * <pre>
 *   mgmt.jobs.{newJob,get,list,delete,run,usage}
 *   mgmt.jobs.runs.{list,get,cancel,rerun}
 *   Job.{save,delete}
 * </pre>
 */
public final class JobsManagementClient {

    private final JobsApi api;
    private final UsageApi usageApi;

    /** Run history and run actions ({@code mgmt.jobs.runs}). */
    public final RunsClient runs;

    public JobsManagementClient(String apiKey, Map<String, String> extraHeaders,
                                Duration timeout, String baseUrl) {
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(baseUrl);
        apiClient.setRequestInterceptor(SmplClient.compositeInterceptor(apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);
        this.api = new JobsApi(apiClient);
        this.usageApi = new UsageApi(apiClient);
        this.runs = new RunsClient(new RunsApi(apiClient));
    }

    /**
     * Returns an unsaved {@link Job} bound to this client. Mutate any remaining
     * fields ({@code description}, {@code enabled}, {@code concurrencyPolicy},
     * etc.) and call {@link Job#save()} to create it.
     *
     * @param id stable caller-supplied unique identifier for the job. Unique
     *     within the account and immutable; the service returns 409 if another
     *     live job already uses this id.
     * @param name human-readable name for the job
     * @param schedule an ISO-8601 datetime, a 5-field UTC cron expression, or
     *     the literal {@code "now"}
     * @param configuration the HTTP request the job performs
     */
    public Job newJob(String id, String name, String schedule, HttpConfig configuration) {
        return new Job(this, id, name, schedule, configuration);
    }

    /** List jobs for the authenticated account (default filters and page size). */
    public List<Job> list() throws ApiException {
        return list(new ListJobsInput());
    }

    /** List jobs for the authenticated account. */
    public List<Job> list(ListJobsInput input) throws ApiException {
        JobListResponse resp = api.listJobs(input.enabled, input.pageNumber, input.pageSize, null);
        List<Job> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (JobResource r : resp.getData()) out.add(fromResource(r));
        }
        return out;
    }

    /**
     * Fetch a single job by id; the returned instance is bound to this client
     * so {@code job.save()} and {@code job.delete()} round-trip back here.
     */
    public Job get(String jobId) throws ApiException {
        JobResponse resp = api.getJob(jobId);
        return fromResource(resp.getData());
    }

    /** Soft-delete a job by id. */
    public void delete(String jobId) throws ApiException {
        api.deleteJob(jobId);
    }

    /** Trigger one immediate {@code MANUAL} run of the job. */
    public Run run(String jobId) throws ApiException {
        return JobsConversions.runFromResource(api.runJobNow(jobId).getData());
    }

    /** Current-period usage counters for the account. */
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
        JobResponse resp = api.createJob(wrapCreateRequest(job));
        return fromResource(resp.getData());
    }

    Job update(Job job) throws ApiException {
        if (job.id == null) {
            throw new IllegalStateException("cannot update a Job with no id");
        }
        JobResponse resp = api.updateJob(job.id, wrapRequest(job.id, job));
        return fromResource(resp.getData());
    }

    // ------------------------------------------------------------------
    // Wire <-> wrapper conversions
    // ------------------------------------------------------------------

    private static com.smplkit.internal.generated.jobs.model.Job genAttrs(com.smplkit.jobs.Job job) {
        com.smplkit.internal.generated.jobs.model.Job attrs =
                new com.smplkit.internal.generated.jobs.model.Job();
        attrs.setName(job.name);
        attrs.setDescription(job.description);
        attrs.setEnabled(job.enabled);
        if (job.type != null) {
            attrs.setType(com.smplkit.internal.generated.jobs.model.Job.TypeEnum.fromValue(job.type));
        }
        attrs.setSchedule(job.schedule);
        attrs.setConfiguration(JobsConversions.configurationToGen(job.configuration));
        if (job.concurrencyPolicy != null) {
            attrs.setConcurrencyPolicy(com.smplkit.internal.generated.jobs.model.Job
                    .ConcurrencyPolicyEnum.fromValue(job.concurrencyPolicy));
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
        job.enabled = a.getEnabled() != null ? a.getEnabled() : true;
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
