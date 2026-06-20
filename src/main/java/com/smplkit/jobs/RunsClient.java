package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;
import com.smplkit.internal.generated.jobs.api.RunsApi;
import com.smplkit.internal.generated.jobs.model.RunListResponse;
import com.smplkit.internal.generated.jobs.model.RunResource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Run history and run actions ({@code jobs.runs}).
 */
public final class RunsClient {

    private final RunsApi api;
    private final String environment;

    RunsClient(RunsApi api, String environment) {
        this.api = api;
        this.environment = environment;
    }

    /**
     * List past runs, most recent first, using default paging across all jobs.
     *
     * @return the runs in the first page, as a list of {@link Run}
     * @throws ApiException if the request fails
     */
    public List<Run> list() throws ApiException {
        return list(new ListRunsInput());
    }

    /**
     * List past runs, most recent first. Cursor paginated.
     *
     * @param input filters and paging for the listing. {@code job} returns only
     *     runs of the job with that id ({@code null} lists across all jobs);
     *     {@code environments} restricts to runs stamped with any of those
     *     environment keys, sent comma-separated as {@code filter[environment]}
     *     ({@code null}/empty falls back to the client's configured environment,
     *     otherwise covers every environment you can access); {@code pageSize}
     *     is the max runs per page ({@code null} uses the server default);
     *     {@code after} is an opaque cursor from a previous page ({@code null}
     *     starts from the first page)
     * @return the runs in this page, as a list of {@link Run}
     * @throws ApiException if the request fails
     */
    public List<Run> list(ListRunsInput input) throws ApiException {
        String filterEnvironment =
                JobsConversions.resolveEnvironmentFilter(input.environments, environment);
        String filterTrigger = JobsConversions.joinTriggers(input.triggers);
        // The generated default (false) would emit ``last_run_only=false`` on
        // every call; pass null unless explicitly requested so the param stays
        // off the wire on default calls.
        Boolean lastRunOnly = input.lastRunOnly ? Boolean.TRUE : null;
        // Positional args track the generated listRuns signature: filterJob,
        // filterStatus, filterEnvironment, filterTrigger, filterCreatedAt,
        // filterStartedAt, filterFinishedAt, filterScheduledFor, lastRunOnly,
        // pageSize, pageAfter, sort. status / date-range / sort filters are out
        // of scope for the wrapper and stay null.
        RunListResponse resp = api.listRuns(
            input.job, null, filterEnvironment, filterTrigger, null, null, null,
            null, lastRunOnly, input.pageSize, input.after, null);
        List<Run> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (RunResource r : resp.getData()) out.add(JobsConversions.runFromResource(r, this));
        }
        return out;
    }

    /**
     * Fetch a single run by its id.
     *
     * @param runId identifier of the run to fetch
     * @return the matching {@link Run}
     * @throws ApiException if the request fails
     */
    public Run get(String runId) throws ApiException {
        return JobsConversions.runFromResource(api.getRun(UUID.fromString(runId)).getData(), this);
    }

    /**
     * Cancel a run that has not finished yet.
     *
     * @param runId identifier of the run to cancel
     * @return the updated {@link Run} reflecting the cancellation
     * @throws ApiException if the request fails
     */
    public Run cancel(String runId) throws ApiException {
        return JobsConversions.runFromResource(api.cancelRun(UUID.fromString(runId)).getData(), this);
    }

    /**
     * Start a new run that repeats a previous one.
     *
     * @param runId identifier of the run to repeat
     * @return the new {@link Run}, with {@code rerunOf} set to {@code runId}
     * @throws ApiException if the request fails
     */
    public Run rerun(String runId) throws ApiException {
        return JobsConversions.runFromResource(api.rerunRun(UUID.fromString(runId)).getData(), this);
    }
}
