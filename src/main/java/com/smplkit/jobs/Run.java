package com.smplkit.jobs;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A single execution of a job (read-only).
 */
public final class Run {
    /** Server-assigned UUID for this run. */
    public final String id;
    /** The id of the job this run belongs to. */
    public final String job;
    /** The job's version at the time the run executed. */
    public final Integer jobVersion;
    /** Why the run exists: {@code SCHEDULE}, {@code MANUAL} (run now), or {@code RERUN}. */
    public final String trigger;
    /** The source run's id; set only when {@code trigger} is {@code RERUN}. */
    public final String rerunOf;
    /** The intended fire time for a scheduled run; {@code null} for manual / rerun runs. */
    public final OffsetDateTime scheduledFor;
    /** Lifecycle state of the run. */
    public final String status;
    /** When execution started. */
    public final OffsetDateTime startedAt;
    /** When execution finished. */
    public final OffsetDateTime finishedAt;
    /** Milliseconds the run waited as {@code PENDING} before starting. */
    public final Integer pendingDurationMs;
    /** Milliseconds the run spent executing. */
    public final Integer runDurationMs;
    /** Milliseconds from enqueue to finish. */
    public final Integer totalDurationMs;
    /** Why a {@code FAILED} run failed; {@code null} otherwise. */
    public final String failureReason;
    /** Free-text failure detail, if any. */
    public final String error;
    /** Snapshot of the request that was sent (header values redacted). */
    public final Map<String, Object> request;
    /** Outcome of the call (status, headers, body, ...). */
    public final Map<String, Object> result;
    /** When the run was enqueued (became {@code PENDING}). */
    public final OffsetDateTime createdAt;

    Run(String id, String job, Integer jobVersion, String trigger, String rerunOf,
        OffsetDateTime scheduledFor, String status, OffsetDateTime startedAt,
        OffsetDateTime finishedAt, Integer pendingDurationMs, Integer runDurationMs,
        Integer totalDurationMs, String failureReason, String error,
        Map<String, Object> request, Map<String, Object> result, OffsetDateTime createdAt) {
        this.id = id;
        this.job = job;
        this.jobVersion = jobVersion;
        this.trigger = trigger;
        this.rerunOf = rerunOf;
        this.scheduledFor = scheduledFor;
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.pendingDurationMs = pendingDurationMs;
        this.runDurationMs = runDurationMs;
        this.totalDurationMs = totalDurationMs;
        this.failureReason = failureReason;
        this.error = error;
        this.request = request;
        this.result = result;
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "Run(id=" + id + ", job=" + job + ", status=" + status + ")";
    }
}
