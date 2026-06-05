package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;
import com.smplkit.internal.generated.jobs.api.RunsApi;
import com.smplkit.internal.generated.jobs.model.RunListResponse;
import com.smplkit.internal.generated.jobs.model.RunResource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Run history and run actions ({@code mgmt.jobs.runs}).
 *
 * <p>Read-only run history plus the {@code cancel} / {@code rerun} run
 * actions. Runs are created and mutated by the jobs service; clients
 * influence them only through these actions.</p>
 */
public final class RunsClient {

    private final RunsApi api;

    RunsClient(RunsApi api) {
        this.api = api;
    }

    /**
     * List runs for the authenticated account, newest first. Cursor
     * paginated: pass {@code pageSize} and the {@code after} cursor from the
     * prior page; pass {@code job} to scope to a single job's history.
     */
    public List<Run> list() throws ApiException {
        return list(new ListRunsInput());
    }

    /** List runs for the authenticated account. */
    public List<Run> list(ListRunsInput input) throws ApiException {
        RunListResponse resp = api.listRuns(input.job, input.pageSize, input.after);
        List<Run> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (RunResource r : resp.getData()) out.add(JobsConversions.runFromResource(r));
        }
        return out;
    }

    /** Fetch a single run by id. */
    public Run get(String runId) throws ApiException {
        return JobsConversions.runFromResource(api.getRun(UUID.fromString(runId)).getData());
    }

    /** Cancel a pending run. */
    public Run cancel(String runId) throws ApiException {
        return JobsConversions.runFromResource(api.cancelRun(UUID.fromString(runId)).getData());
    }

    /** Re-run a prior run, spawning a new {@code RERUN} run. */
    public Run rerun(String runId) throws ApiException {
        return JobsConversions.runFromResource(api.rerunRun(UUID.fromString(runId)).getData());
    }
}
